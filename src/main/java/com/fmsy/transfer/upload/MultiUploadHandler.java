package com.fmsy.transfer.upload;

import com.fmsy.exception.FlagCheckException;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.transfer.TransferUtils;
import com.fmsy.util.BooleanUtils;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * UPLOAD_MULTI 场景的 SERIAL 模式：通配符匹配目录所有文件，
 * 单次 FTP 连接内预扫描后并行上传到同一张表。
 *
 * <p>
 * 配置路径中的 {@code {FILE_NAME}} 占位符替换为 {@code *}（通配符），
 * 用于 FTP 文件列表匹配。
 * </p>
 * <p>
 * 示例：{@code /data/input/{YYYYMMDD}/BR{FILE_NAME}.csv}
 * → {@code *} 匹配所有 BR 开头的 csv 文件。
 * </p>
 *
 * <p>
 * 阶段顺序：<br>
 * 预扫描（单次 FTP 连接内完成列表+标志检查+孤立标志文件迁 error）→ 清表 → 并行落库+前后审计 → 汇总后操作
 *
 * <p>
 * 前稽核已合并到 insertDataAndVerify 的落库后阶段，与后审计使用
 * CloseableIterator.getRecordCount() 统一校验，无需额外 FTP 流做独立前稽核。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiUploadHandler implements TransferHandler {

    static final int TASK_FAIL = -2;

    private final UploadSupport support;
    private final TransferSupport transferSupport;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final IntFunction<ExecutorService> batchExecutorFactory;

    /**
     * <ol>
     * <li>解析配置路径，{FILE_NAME} → *，在单次 FTP 连接内完成列表 + 标志检查 + 异常文件处理</li>
     * <li>统一清表（clearTableFlag=Y 时）</li>
     * <li>对有效文件并行执行落库（insertDataAndVerify + postProcess），
     * 前稽核与后审计在落库后统一校验</li>
     * <li>汇总后操作（postProcess）</li>
     * </ol>
     *
     * <p>
     * 路径配置示例：{@code /data/input/{YYYYMMDD}/BR{FILE_NAME}.csv}
     * — SERIAL 模式将 {FILE_NAME} 替换为 {@code *}，匹配所有 BR 开头的 csv 文件。
     * </p>
     */
    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Multi-file upload from directory, command={}, table={}",
                command.getId(), config.getTableName());
        String ftpName = config.getFtpName();

        // 解析路径中的 {YYYYMMDD} 等占位符，保留 ResolvedPath 供后操作回退
        ResolvedPath resolvedDir = transferSupport.resolveFilePath(config.getFilePath(), command);
        String resolvedPath = resolvedDir != null ? resolvedDir.fullPath() : null;
        // {FILE_NAME} → *（通配符匹配），用于 FTP 文件列表
        String listPattern = resolvedPath != null ? resolvedPath.replace("{FILE_NAME}", "*") : null;

        /* ---------- Phase 1: 单次 FTP 连接内完成列表 + 标志检查 + 异常文件处理 ---------- */
        List<String> validFiles = prescanAndValidate(ftpName, listPattern, config);
        if (validFiles == null) {
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Failed to list directory");
            return;
        }
        if (validFiles.isEmpty()) {
            log.info("No valid data files after prescan (pattern: {})", listPattern);
            result.setOutcome(0, TransferSupport.determineMainStatus(false, 0, 1), "");
            return;
        }
        log.info("Prescan: {} valid data files (pattern: {})", validFiles.size(), listPattern);

        /* ---------- Phase 2: 清表 ---------- */
        if (BooleanUtils.isYes(config.getClearTableFlag())) {
            support.truncateTable(config);
        }

        /* ---------- Phase 3: 并行落库 ---------- */
        int concurrency = config.getConcurrency() != null ? config.getConcurrency() : 3;
        int failedCount = 0;
        FieldMapping sharedMapping = fieldMappingBuilder.buildForUpload(config, null);

        ExecutorService insertExecutor = batchExecutorFactory.apply(concurrency);
        List<Future<Integer>> insertFutures = new ArrayList<>();
        for (String filePath : validFiles) {
            insertFutures.add(insertExecutor.submit(
                    () -> insertSingleFile(ftpName, filePath, ResolvedPath.of(filePath),
                            config, sharedMapping, null)));
        }
        shutdownExecutor(insertExecutor);

        // 收集落库结果
        int totalRecords = 0;
        for (int i = 0; i < insertFutures.size(); i++) {
            try {
                Integer recordCount = insertFutures.get(i).get(30, TimeUnit.MINUTES);
                if (recordCount != null && recordCount > 0) {
                    totalRecords += recordCount;
                } else {
                    failedCount++;
                }
            } catch (Exception e) {
                log.error("Insert failed for file: {}", validFiles.get(i), e);
                failedCount++;
            }
        }

        // 有文件失败时仅日志告警，不清表不回滚（清表已前置，单文件失败已在各自事务中回滚）
        if (failedCount > 0) {
            log.warn("{} file(s) failed, individual transactions rolled back", failedCount);
        }

        result.setOutcome(totalRecords, ColumnNames.STATUS_SUCCESS, "");
    }

    // ==================== 落库阶段（单文件） ====================

    /**
     * 对单个文件执行落库操作（insertDataAndVerify + postProcess）。
     *
     * <p>
     * SERIAL 模式（目录通配符）下 auditCount 传入 null，跳过稽核数校验。
     * </p>
     *
     * @param mapping    预构建的 FieldMapping（所有文件共享，避免重复构建）
     * @param auditCount 稽核数（SERIAL 模式传 null）
     * @return 成功插入的记录数
     */
    private Integer insertSingleFile(String ftpName, String filePath, ResolvedPath fileInfo,
            TransferConfig config, FieldMapping mapping, Integer auditCount) {
        try {
            return transferSupport.executeWithClient(ftpName, client -> {
                // Phase 1: preCheck — 标志文件检查（含 FLAG 内容比对）
                UploadSupport.UploadResult checkResult = support.preCheck(client, config, fileInfo, filePath);
                if (checkResult != null) {
                    log.warn("Pre-check failed for {}, skipping", filePath);
                    return 0;
                }

                // Phase 2: 落库 + 前后审计（单事务），使用预构建的 FieldMapping（避免重复查表元数据）
                int count = support.insertDataAndVerify(client, config, mapping, filePath, auditCount);

                // Phase 3: 文件级后操作
                support.postProcess(client, config, fileInfo, count);

                log.info("Uploaded file: {} ({} records)", filePath, count);
                return count;
            });
        } catch (FlagCheckException e) {
            // FLAG 比对失败 → 迁文件到 error 目录
            log.warn("FLAG check failed, moving to error: {}", filePath);
            support.moveDataAndFlagToErrorDir(ftpName, filePath, config);
            return TASK_FAIL;
        } catch (Exception e) {
            // 前稽核/后审计或其他异常 → 迁文件到 error 目录，文件级事务已自行回滚
            log.error("Insert failed for {}, moving to error: {}", filePath, e.getMessage());
            support.moveDataAndFlagToErrorDir(ftpName, filePath, config);
            return TASK_FAIL;
        }
    }

    // ==================== 预扫描工具 ====================

    /**
     * 预扫描文件列表，过滤出有效的数据文件。
     *
     * <p>
     * 对于有数据文件但无对应标志文件的，日志告警并跳过。
     * </p>
     */
    List<String> prescanDataFiles(String[] allFiles, String flagPattern) {
        if (allFiles == null || allFiles.length == 0)
            return List.of();
        if (flagPattern == null) {
            return new ArrayList<>(List.of(allFiles));
        }

        // 预构建 ResolvedPath，一次解析供 name() 和 resolveFlagName 复用，避免重复的字符串操作
        List<ResolvedPath> fileInfos = new ArrayList<>(allFiles.length);
        Set<String> allNames = new HashSet<>();
        for (String f : allFiles) {
            ResolvedPath info = ResolvedPath.of(f);
            fileInfos.add(info);
            allNames.add(info.name());
        }

        List<String> dataFiles = new ArrayList<>();
        for (int i = 0; i < allFiles.length; i++) {
            ResolvedPath fileInfo = fileInfos.get(i);
            String name = fileInfo.name();

            String expectedFlagName = resolveFlagName(flagPattern, fileInfo);
            if (expectedFlagName == null) {
                dataFiles.add(allFiles[i]);
                continue;
            }

            // 跳过标志文件本身
            String expectedName = fileNameOnly(expectedFlagName);
            if (name.equals(expectedName)) {
                continue;
            }
            if (allNames.contains(expectedName)) {
                dataFiles.add(allFiles[i]);
            } else {
                log.warn("Data file {} has no corresponding flag file (expected: {}), skipping",
                        allFiles[i], expectedFlagName);
            }
        }
        return dataFiles;
    }

    static String resolveFlagName(String flagPattern, ResolvedPath fileInfo) {
        if (flagPattern == null || fileInfo == null)
            return null;
        String resolved = TransferSupport.expandPathVariables(flagPattern, fileInfo);
        if (!resolved.startsWith("/") && fileInfo.dir() != null && !fileInfo.dir().isEmpty()) {
            resolved = fileInfo.dir() + "/" + resolved;
        }
        return resolved;
    }

    private static String fileNameOnly(String path) {
        if (path == null)
            return null;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * 单次 FTP 连接内完成：文件列表 + 标志检查 + 异常文件处理。
     *
     * <p>
     * 委托给 {@link #prescanDataFiles} 做基础过滤后，
     * 再单独处理孤立标志文件迁移。
     * </p>
     *
     * @param ftpName     FTP 连接名
     * @param listPattern 文件列表通配符模式
     * @param config      传输配置
     * @return 有效数据文件完整路径列表；null 表示列表失败
     */
    private List<String> prescanAndValidate(String ftpName, String listPattern,
            TransferConfig config) throws Exception {
        return transferSupport.executeWithClient(ftpName, client -> {
            String[] allFiles = client.listFiles(listPattern);
            if (allFiles == null) return null;
            if (allFiles.length == 0) return List.of();

            String flagPattern = UploadSupport.extractFlagPathPattern(config.getPreOperations());
            if (flagPattern == null) {
                log.info("Prescan: {} files (no flag pattern)", allFiles.length);
                return new ArrayList<>(List.of(allFiles));
            }

            // Phase A: prescanDataFiles 过滤有效数据文件（含标志文件过滤和无标志告警）
            List<String> validFiles = prescanDataFiles(allFiles, flagPattern);

            // Phase B: 孤立标志文件迁移到 error 目录
            moveOrphanedFlagFiles(client, allFiles, flagPattern, validFiles);

            log.info("Prescan result: {} valid files (pattern: {})", validFiles.size(), listPattern);
            return validFiles;
        });
    }

    /**
     * 扫描孤立标志文件并迁移到 error 目录。
     *
     * <p>
     * 孤立标志文件 = 有标志文件但 prescanDataFiles 中没有匹配的数据文件。
     * </p>
     */
    private void moveOrphanedFlagFiles(FtpClient client, String[] allFiles,
            String flagPattern, List<String> validDataFiles) throws Exception {
        // 收集有效数据文件对应的标志文件名
        Set<String> validFlagNames = new HashSet<>();
        for (String f : validDataFiles) {
            String expectedName = resolveFlagName(flagPattern, ResolvedPath.of(f));
            if (expectedName != null) {
                validFlagNames.add(fileNameOnly(expectedName));
            }
        }

        // 遍历所有文件，将没有对应数据文件的标志文件迁到 error
        int orphanedCount = 0;
        for (String file : allFiles) {
            String expectedFlagName = resolveFlagName(flagPattern, ResolvedPath.of(file));
            if (expectedFlagName == null) continue;

            String name = fileNameOnly(file);
            String expectedName = fileNameOnly(expectedFlagName);

            if (name.equals(expectedName) && !validFlagNames.contains(name)) {
                log.warn("Orphaned flag file, moving to error: {}", file);
                try {
                    client.moveToErrorDir(file);
                    orphanedCount++;
                } catch (Exception e) {
                    log.error("Failed to move orphaned flag to error: {}", file, e);
                }
            }
        }

        if (orphanedCount > 0) {
            log.info("Moved {} orphaned flag(s) to error dir", orphanedCount);
        }
    }

    // ==================== 通用工具 ====================

    private static void shutdownExecutor(ExecutorService executor) {
        TransferUtils.shutdownExecutor(executor, 1, TimeUnit.MINUTES, "Upload executor");
    }

}