package com.fmsy.transfer.upload;

import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.exception.TransferException;
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
 * <p>配置路径中的 {@code {FILE_NAME}} 占位符替换为 {@code *}（通配符），
 * 用于 FTP 文件列表匹配。</p>
 * <p>示例：{@code /data/input/{YYYYMMDD}/BR{FILE_NAME}.csv}
 * → {@code *} 匹配所有 BR 开头的 csv 文件。</p>
 *
 * <p>阶段顺序：<br>
 * 预扫描（单次 FTP 连接内完成列表+标志检查+孤立标志文件迁 error）→ 清表 → 并行落库+前后审计 → 汇总后操作
 *
 * <p>前稽核已合并到 insertDataAndVerify 的落库后阶段，与后审计使用
 * CloseableIterator.getRecordCount() 统一校验，无需额外 FTP 流做独立前稽核。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiUploadHandler implements TransferHandler {

    static final int TASK_SKIP = -1;
    static final int TASK_FAIL = -2;

    private final UploadSupport support;
    private final TransferSupport transferSupport;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final IntFunction<ExecutorService> batchExecutorFactory;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        handleSerial(command, config, result);
    }

    // ==================== SERIAL 模式：目录通配符 + 预扫描 + 并发 ====================

    /**
     * SERIAL 模式完整流程：
     * <ol>
     *   <li>解析配置路径，{FILE_NAME} → *，在单次 FTP 连接内完成列表 + 标志检查 + 异常文件处理</li>
     *   <li>统一清表（clearTableFlag=Y 时）</li>
     *   <li>对有效文件并行执行落库（insertDataAndVerify + postProcess），
     *       前稽核与后审计在落库后统一校验</li>
     *   <li>汇总后操作（postProcess）</li>
     * </ol>
     *
     * <p>路径配置示例：{@code /data/input/{YYYYMMDD}/BR{FILE_NAME}.csv}
     * — SERIAL 模式将 {FILE_NAME} 替换为 {@code *}，匹配所有 BR 开头的 csv 文件。</p>
     */
    private void handleSerial(Command command, TransferConfig config, Result result) throws Exception {
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

        // 落库失败处理
        if (failedCount > 0 && config.getEmptyDataHandling() == EmptyDataHandling.ERROR) {
            rollbackOnFailure(config, failedCount);
        }

        result.setOutcome(totalRecords,
                TransferSupport.determineMainStatus(failedCount == 0, failedCount, 0), "");
    }

    // ==================== 落库阶段（单文件） ====================

    /**
     * 对单个文件执行落库操作（insertDataAndVerify + postProcess）。
     *
     * <p>SERIAL 模式（目录通配符）下 auditCount 传入 null，跳过稽核数校验。</p>
     *
     * @param mapping    预构建的 FieldMapping（所有文件共享，避免重复构建）
     * @param auditCount 稽核数（SERIAL 模式传 null）
     * @return 成功插入的记录数
     */
    private Integer insertSingleFile(String ftpName, String filePath, ResolvedPath fileInfo,
                                     TransferConfig config, FieldMapping mapping, Integer auditCount) {
        try {
            return transferSupport.executeWithClient(ftpName, client -> {
                // 落库 + 前后审计（单事务），使用预构建的 FieldMapping（避免重复查表元数据）
                // auditCount 由 insertAndVerifyPerFileInTx 在落库后与后审计同时校验
                int count = support.insertDataAndVerify(client, config, mapping, filePath, auditCount);

                // 文件级后操作
                support.postProcess(client, config, fileInfo, count);

                log.info("Uploaded file: {} ({} records)", filePath, count);
                return count;
            });
        } catch (Exception e) {
            log.error("Insert failed for {}: {}", filePath, e.getMessage(), e);
            return TASK_FAIL;
        }
    }


    // ==================== 预扫描工具 ====================

    /**
     * 预扫描文件列表，过滤出有效的数据文件。
     *
     * <p>对于有数据文件但无对应标志文件的，日志告警并跳过。</p>
     */
    List<String> prescanDataFiles(String[] allFiles, String flagPattern) {
        if (allFiles == null || allFiles.length == 0) return List.of();
        if (flagPattern == null) {
            return new ArrayList<>(List.of(allFiles));
        }

        Set<String> allNames = new HashSet<>();
        for (String f : allFiles) {
            allNames.add(fileNameOnly(f));
        }

        List<String> dataFiles = new ArrayList<>();
        for (String file : allFiles) {
            String name = fileNameOnly(file);

            String expectedFlagName = resolveFlagName(flagPattern, ResolvedPath.of(file));
            if (expectedFlagName == null) {
                dataFiles.add(file);
                continue;
            }

            String expectedName = fileNameOnly(expectedFlagName);
            if (name.equals(expectedName)) {
                continue;
            }
            if (allNames.contains(expectedName)) {
                dataFiles.add(file);
            } else {
                log.warn("Data file {} has no corresponding flag file (expected: {}), skipping",
                        file, expectedFlagName);
            }
        }
        return dataFiles;
    }

    static String resolveFlagName(String flagPattern, ResolvedPath fileInfo) {
        if (flagPattern == null || fileInfo == null) return null;
        String resolved = TransferSupport.expandPathVariables(flagPattern, fileInfo);
        if (!resolved.startsWith("/") && fileInfo.dir() != null && !fileInfo.dir().isEmpty()) {
            resolved = fileInfo.dir() + "/" + resolved;
        }
        return resolved;
    }

    private static String fileNameOnly(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * 单次 FTP 连接内完成：文件列表 + 标志检查 + 异常文件处理。
     *
     * <p>合并了原来的 {@code listFiles} + {@code prescanDataFiles} + 逐文件 preCheck，
     * 减少 FTP 连接次数（从 1+N 次降至 1 次）。</p>
     *
     * <p>处理逻辑：</p>
     * <ul>
     *   <li>数据文件有对应标志 → 加入有效列表</li>
     *   <li>数据文件无对应标志 → 跳过（仅日志警告）</li>
     *   <li>孤立的标志文件（无对应数据文件）→ 迁到 error 目录（自动重命名 + 时间戳）</li>
     * </ul>
     *
     * @param ftpName     FTP 连接名
     * @param listPattern 文件列表通配符模式
     * @param config      传输配置
     * @return 有效数据文件完整路径列表；null 表示列表失败
     */
    private List<String> prescanAndValidate(String ftpName, String listPattern,
                                             TransferConfig config) throws Exception {
        return transferSupport.executeWithClient(ftpName, client -> {
            // 1. 列出所有文件
            String[] allFiles = client.listFiles(listPattern);
            if (allFiles == null) return null;
            if (allFiles.length == 0) return List.of();
            log.debug("Prescan: {} files listed (pattern: {})", allFiles.length, listPattern);

            // 2. 提取标志模式
            String flagPattern = UploadSupport.extractFlagPathPattern(config.getPreOperations());
            if (flagPattern == null) {
                // 无标志检查 → 所有文件都是数据文件
                log.info("Prescan: {} files (no flag pattern)", allFiles.length);
                return new ArrayList<>(List.of(allFiles));
            }

            // 3. 构建文件名集合
            Set<String> allNames = new HashSet<>();
            for (String f : allFiles) allNames.add(fileNameOnly(f));

            // 4. 分类处理
            List<String> validDataFiles = new ArrayList<>();
            List<String> noFlagDataFiles = new ArrayList<>();
            List<String> flagFiles = new ArrayList<>();
            Set<String> validFlagNames = new HashSet<>();

            for (String file : allFiles) {
                String name = fileNameOnly(file);
                String expectedFlagName = resolveFlagName(flagPattern, ResolvedPath.of(file));
                if (expectedFlagName == null) {
                    validDataFiles.add(file);
                    continue;
                }
                String expectedName = fileNameOnly(expectedFlagName);

                if (name.equals(expectedName)) {
                    // 这是标志文件本身
                    flagFiles.add(file);
                    continue;
                }

                if (allNames.contains(expectedName)) {
                    // 数据文件有对应标志 → 有效
                    validDataFiles.add(file);
                    validFlagNames.add(expectedName);
                } else {
                    // 数据文件无对应标志 → 跳过
                    noFlagDataFiles.add(file);
                }
            }

            // 5. 日志警告：无标志的数据文件（跳过，不迁 error）
            for (String badFile : noFlagDataFiles) {
                log.warn("Data file without flag, skipping: {}", badFile);
            }

            // 6. 处理孤立的标志文件（迁到 error 目录，自动重命名 + 时间戳）
            int orphanedCount = 0;
            for (String flagFile : flagFiles) {
                String name = fileNameOnly(flagFile);
                if (!validFlagNames.contains(name)) {
                    log.warn("Orphaned flag file, moving to error: {}", flagFile);
                    try {
                        client.moveToErrorDir(flagFile);
                        orphanedCount++;
                    } catch (Exception e) {
                        log.error("Failed to move orphaned flag to error: {}", flagFile, e);
                    }
                }
            }

            log.info("Prescan: {} valid, {} skipped (no flag), {} orphaned flags moved to error",
                    validDataFiles.size(), noFlagDataFiles.size(), orphanedCount);

            return validDataFiles;
        });
    }

    // ==================== 通用工具 ====================

    private static void shutdownExecutor(ExecutorService executor) {
        TransferUtils.shutdownExecutor(executor, 1, TimeUnit.MINUTES, "Upload executor");
    }

    private void rollbackOnFailure(TransferConfig config, int failedCount) throws TransferException {
        if (BooleanUtils.isYes(config.getClearTableFlag())) {
            support.truncateTable(config);
            log.error("Rolled back table {} due to {} failed file(s)",
                    config.getTableName(), failedCount);
        } else {
            log.error("Post-audit failed for {} file(s); incremental mode, preserving existing data in {}.{}",
                    failedCount, config.getDbName(), config.getTableName());
        }
        throw new TransferException("POST_AUDIT_FAILED",
                "Post-audit failed for " + failedCount + " file(s)");
    }

}