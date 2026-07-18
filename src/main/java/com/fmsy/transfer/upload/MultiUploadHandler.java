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
import java.util.regex.Pattern;

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
     * 将标志文件名模式转换为正则表达式，用于匹配标志文件。
     *
     * <p>
     * 已知变量（{stem}、{name}、{ext}、{dir}、{dn}、{up}）替换为 {@code [^/]+?}，
     * 字面量部分做 {@link Pattern#quote} 转义，避免正则元字符冲突。
     * </p>
     *
     * <p>
     * 示例：
     * <ul>
     *   <li>{@code {stem}.ok} → {@code ^[^/]+?\.ok$}</li>
     *   <li>{@code {name}.ok} → {@code ^[^/]+?\.ok$}</li>
     *   <li>{@code filename.ok} → {@code ^filename\.ok$}</li>
     *   <li>{@code flag_{stem}.ok} → {@code ^flag_[^/]+?\.ok$}</li>
     * </ul>
     * </p>
     */
    static Pattern toFlagRegex(String flagPattern) {
        if (flagPattern == null) return null;
        // 按变量边界分割，保留变量作为独立元素
        String[] parts = flagPattern.split("(?=\\{)|(?<=\\})", -1);
        StringBuilder sb = new StringBuilder("^");
        for (String part : parts) {
            if (isBracketVariable(part)) {
                sb.append("[^/]+?");
            } else {
                sb.append(Pattern.quote(part));
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }

    private static final Set<String> KNOWN_VARS = Set.of("stem", "name", "ext", "dir", "dn", "up");

    private static boolean isBracketVariable(String s) {
        return s != null && s.length() > 2
                && s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}'
                && KNOWN_VARS.contains(s.substring(1, s.length() - 1));
    }

    /**
     * 预扫描：用正则从文件列表中分离出标志文件，再交叉筛选有效数据文件。
     *
     * <p>
     * 对比旧方案（用 {@link #resolveFlagName} 对文件自身做自识别），
     * 正则匹配能兼容所有变量命名模式（{stem}、{name} 等），
     * 不会出现 {@code {name}.ok} 模式下标志文件自识别失败的问题。
     * </p>
     *
     * <p>
     * 流程：
     * <ol>
     *   <li>用 {@link #toFlagRegex} 将标志模式转为正则，扫描文件列表分离出 标志文件集合 和 候选数据文件</li>
     *   <li>对每个候选数据文件，计算期望标志文件名，检查是否在标志文件集合中</li>
     *   <li>有 → 加入有效数据文件；无 → 日志告警并跳过</li>
     * </ol>
     * </p>
     *
     * @param allFiles    FTP 目录列表全部文件
     * @param flagPattern 标志文件名模式（如 {@code {stem}.ok}），null 表示无 flag 模式
     * @return 有效数据文件（有对应标志文件的数据文件）列表
     */
    List<String> prescanDataFiles(String[] allFiles, String flagPattern) {
        return prescanDataFiles(allFiles, flagPattern, null);
    }

    /**
     * 预扫描：用正则从文件列表中分离出标志文件，再交叉筛选有效数据文件。
     *
     * <p>当 {@code flagPattern} 为 null（无 flag 模式）时，使用 {@code listRegex}
     * 过滤文件列表，只保留匹配数据文件命名模式的文件。
     * </p>
     *
     * @param allFiles    FTP 目录列表全部文件
     * @param flagPattern 标志文件名模式，null 表示无 flag 模式
     * @param listRegex   数据文件命名正则（无 flag 模式时使用，用于过滤额外文件）
     * @return 有效数据文件列表
     */
    List<String> prescanDataFiles(String[] allFiles, String flagPattern, Pattern listRegex) {
        if (allFiles == null || allFiles.length == 0)
            return List.of();
        if (flagPattern == null) {
            // 无 flag 模式：用 glob 正则过滤，只保留匹配数据文件命名模式的文件
            if (listRegex == null) {
                return new ArrayList<>(List.of(allFiles));
            }
            List<String> filtered = new ArrayList<>();
            for (String f : allFiles) {
                if (listRegex.matcher(ResolvedPath.of(f).name()).matches()) {
                    filtered.add(f);
                }
            }
            return filtered;
        }

        Pattern flagRegex = toFlagRegex(flagPattern);

        // Step 1: 用正则分离标志文件 和 候选数据文件
        Set<String> flagNames = new HashSet<>();
        List<String> candidateDataFiles = new ArrayList<>();
        for (String f : allFiles) {
            String name = ResolvedPath.of(f).name();
            if (flagRegex.matcher(name).matches()) {
                flagNames.add(name);
            } else {
                candidateDataFiles.add(f);
            }
        }

        // Step 1.5: 候选数据文件也需匹配数据文件命名模式，过滤掉额外文件
        if (listRegex != null) {
            candidateDataFiles.removeIf(f ->
                    !listRegex.matcher(ResolvedPath.of(f).name()).matches());
        }

        // Step 2: 交叉筛选——有对应标志的数据文件有效，无标志的告警跳过
        List<String> validDataFiles = new ArrayList<>();
        for (String f : candidateDataFiles) {
            ResolvedPath fileInfo = ResolvedPath.of(f);
            String expectedFlagName = resolveFlagName(flagPattern, fileInfo);
            String expectedFlagFileName = fileNameOnly(expectedFlagName);

            if (flagNames.contains(expectedFlagFileName)) {
                validDataFiles.add(f);
            } else {
                log.warn("Data file {} has no corresponding flag file (expected: {}), skipping",
                        f, expectedFlagName);
            }
        }
        return validDataFiles;
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
     * 将 glob 通配符模式转为正则，用于无 flag 模式时对数据文件做二次过滤。
     * <p>
     * 示例：{@code BR*.csv} → {@code ^BR[^/]*\.csv$}
     * </p>
     */
    static Pattern globToRegex(String glob) {
        if (glob == null) return null;
        // 提取文件名部分（glob 通常是完整路径，如 /data/BR*.csv）
        int lastSlash = glob.lastIndexOf('/');
        String pattern = lastSlash >= 0 ? glob.substring(lastSlash + 1) : glob;
        // 若去掉目录后无通配符，说明 listPattern 不含 {FILE_NAME} 占位符，无需过滤
        if (pattern.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                sb.append("[^/]*");
            } else if (c == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
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

            // Phase A: prescanDataFiles 过滤有效数据文件
            // 用 listPattern 转正则做二次过滤，排除 FTP 返回的额外文件
            Pattern listRegex = globToRegex(listPattern);
            List<String> validFiles = prescanDataFiles(allFiles, flagPattern, listRegex);

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
     * 用正则匹配定位标志文件，然后检查是否在有效数据文件对应的期望标志集合中。
     * 不在则视为孤立标志文件，迁移到 error 目录。
     * </p>
     */
    private void moveOrphanedFlagFiles(FtpClient client, String[] allFiles,
            String flagPattern, List<String> validDataFiles) throws Exception {
        Pattern flagRegex = toFlagRegex(flagPattern);
        if (flagRegex == null) return;

        // Step 1: 收集有效数据文件对应的期望标志文件名
        Set<String> expectedFlagNames = new HashSet<>();
        for (String f : validDataFiles) {
            String expected = resolveFlagName(flagPattern, ResolvedPath.of(f));
            if (expected != null) {
                expectedFlagNames.add(fileNameOnly(expected));
            }
        }

        // Step 2: 用正则定位标志文件，不在期望集合中的为孤立标志
        int orphanedCount = 0;
        for (String file : allFiles) {
            String name = ResolvedPath.of(file).name();
            if (!flagRegex.matcher(name).matches()) continue; // 不是标志文件

            if (!expectedFlagNames.contains(name)) {
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