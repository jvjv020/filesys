package com.fmsy.transfer.upload;

import com.fmsy.enums.CommandType;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.exception.TransferException;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.BooleanUtils;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * UPLOAD_MULTI 场景:按指令类型分流。
 *
 * <ul>
 *   <li>{@link CommandType#SERIAL}(null) — 通配符匹配目录所有文件,预扫描后并行上传到同一张表</li>
 *   <li>{@link CommandType#BATCH}('R') — 按明细表逐文件上传(顺序执行)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiUploadHandler implements TransferHandler {

    static final int TASK_SKIP = -1;
    static final int TASK_FAIL = -2;

    /** 已知的标志文件后缀（用于预扫描过滤） */
    private static final Set<String> KNOWN_FLAG_SUFFIXES = Set.of(".OK", ".ok", ".ready", ".flag", ".flg");

    private final DetailRepository detailRepository;
    private final UploadSupport support;
    private final TransferSupport transferSupport;
    private final IntFunction<ExecutorService> batchExecutorFactory;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        if (command.getCommandType() == CommandType.BATCH) {
            handleBatch(command, config, result);
        } else {
            handleSerial(command, config, result);
        }
    }

    // ==================== SERIAL 模式：目录通配符 + 预扫描 + 并发 ====================

    private void handleSerial(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Multi-file upload from directory");
        String ftpName = config.getFtpName();

        ResolvedPath dirInfo = transferSupport.resolveFilePath(config.getFilePath(), command);
        String listPattern = toGlobPattern(dirInfo != null ? dirInfo.fullPath() : null);

        // Phase 1: 列出所有文件 + 预扫描（过滤标志文件、告警缺少标志文件的数据文件）
        String[] allFiles = listFiles(ftpName, listPattern);
        if (allFiles == null) {
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Failed to list directory");
            return;
        }
        if (allFiles.length == 0) {
            log.info("No files found in directory: {} (pattern: {})", dirInfo, listPattern);
            result.setOutcome(0, UploadSupport.determineMainStatus(
                    new UploadSupport.UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED)), "");
            return;
        }
        log.info("Found {} files in directory: {} (pattern: {})", allFiles.length, dirInfo, listPattern);

        // 预扫描：过滤出有效的数据文件
        String flagPattern = extractFlagPathPattern(config.getPreOperations());
        List<String> dataFiles = prescanDataFiles(allFiles, flagPattern);

        if (dataFiles.isEmpty()) {
            log.info("No valid data files after pre-scan (all filtered out)");
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "No valid data files with flag files found");
            return;
        }
        log.info("Pre-scan: {} valid data files out of {} total files", dataFiles.size(), allFiles.length);

        // Phase 2: DB truncate + 并行文件上传
        if (BooleanUtils.isYes(config.getClearTableFlag())) {
            support.truncateTable(config);
        }

        int concurrency = config.getConcurrency() != null ? config.getConcurrency() : 3;
        ExecutorService executor = batchExecutorFactory.apply(concurrency);

        int totalRecords = 0;
        int successCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        ResolvedPath lastFileInfo = null;
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String filePath : dataFiles) {
                futures.add(executor.submit(new FileTask(ftpName, filePath, command, config)));
            }

            for (int i = 0; i < futures.size(); i++) {
                try {
                    Integer taskResult = futures.get(i).get(30, TimeUnit.MINUTES);
                    if (taskResult != null && taskResult > 0) {
                        totalRecords += taskResult;
                        successCount++;
                        lastFileInfo = ResolvedPath.of(dataFiles.get(i));
                    } else if (taskResult != null && taskResult == TASK_SKIP) {
                        skippedCount++;
                    } else {
                        failedCount++;
                    }
                } catch (Exception e) {
                    log.error("Failed to upload file: {}", dataFiles.get(i), e);
                    failedCount++;
                }
            }
        } finally {
            shutdownExecutor(executor);
        }

        if (failedCount > 0 && config.getEmptyDataHandling() == EmptyDataHandling.ERROR) {
            rollbackOnFailure(config, failedCount);
        }

        // Phase 3: postProcess（以最后一个成功文件的目录信息）
        int finalTotalRecords = totalRecords;
        ResolvedPath finalLastFileInfo = lastFileInfo;
        try {
            transferSupport.executeWithClient(ftpName, client -> {
                transferSupport.postProcess(client, config, finalLastFileInfo != null ? finalLastFileInfo : dirInfo, Map.of("C", String.valueOf(finalTotalRecords)));
                return null;
            });
        } catch (Exception e) {
            log.error("Phase 3 (postProcess) failed for serial upload: {}", e.getMessage(), e);
            result.setOutcome(totalRecords, ColumnNames.STATUS_ERROR,
                    "Data uploaded successfully (" + totalRecords + " records) but post-processing failed: "
                            + e.getMessage());
            return;
        }

        result.setOutcome(totalRecords,
                UploadSupport.determineMainStatus(new UploadSupport.UploadResult(
                        totalRecords, successCount, skippedCount, failedCount, null)), "");
    }

    /**
     * 从前置操作字符串中提取 FLAG 路径模式。
     * 例如 {@code "FLAG:{stem}.OK,READY:other"} → {@code "{stem}.OK"}。
     *
     * @param preOps 前置操作字符串，为 null 或不含 FLAG/READY 时返回 null
     * @return 第一个 FLAG 或 READY 操作的路径模式，不含关键字和 mode 后缀
     */
    static String extractFlagPathPattern(String preOps) {
        if (preOps == null || preOps.isEmpty()) return null;
        for (String op : preOps.split(",")) {
            op = op.trim();
            String pathPart = null;
            if (op.startsWith("FLAG:")) {
                pathPart = op.substring(5).trim();
            } else if (op.startsWith("READY:")) {
                pathPart = op.substring(6).trim();
            }
            if (pathPart == null || pathPart.isEmpty()) continue;
            // 去掉 mode 后缀（;mode 部分）
            int semicolon = pathPart.indexOf(';');
            return semicolon > 0 ? pathPart.substring(0, semicolon).trim() : pathPart;
        }
        return null;
    }

    /**
     * 预扫描文件列表，过滤出有效的数据文件。
     *
     * <p>对于有数据文件但无对应标志文件的，日志告警并跳过。
     * 已知标志文件后缀（{@link #KNOWN_FLAG_SUFFIXES}）的文件会被静默过滤。
     *
     * @param allFiles    目录中所有文件的列表
     * @param flagPattern FLAG 路径模式（如 {@code "{stem}.OK"}），为 null 时不执行预扫描
     * @return 有效的数据文件列表
     */
    List<String> prescanDataFiles(String[] allFiles, String flagPattern) {
        if (allFiles == null || allFiles.length == 0) return List.of();
        if (flagPattern == null) {
            // 没有配置 FLAG/READY 操作，全部视为数据文件
            return new ArrayList<>(List.of(allFiles));
        }

        // 构建文件名集合（统一用最后一段文件名比较）
        Set<String> allNames = new HashSet<>();
        for (String f : allFiles) {
            allNames.add(fileNameOnly(f));
        }

        List<String> dataFiles = new ArrayList<>();
        for (String file : allFiles) {
            String name = fileNameOnly(file);

            // 检查本文件是否是已知的标志文件
            if (isKnownFlagFile(name, allNames)) continue;

            // 为本文件解析期望的标志文件路径
            String expectedFlagName = resolveFlagName(flagPattern, ResolvedPath.of(file));
            if (expectedFlagName == null) {
                // 路径模式无法解析（如不含 {stem}/{name} 的绝对路径），直接包含
                dataFiles.add(file);
                continue;
            }

            String expectedName = fileNameOnly(expectedFlagName);
            if (name.equals(expectedName)) {
                // 期望的标志文件路径就是本文件自己 → 本文件是标志文件，静默跳过
                continue;
            }
            if (allNames.contains(expectedName)) {
                // 标志文件存在 → 有效数据文件
                dataFiles.add(file);
            } else {
                // 数据文件存在但标志文件不存在 → 告警跳过
                log.warn("Data file {} has no corresponding flag file (expected: {}), skipping",
                        file, expectedFlagName);
            }
        }
        return dataFiles;
    }

    /**
     * 判断文件名是否是已知的标志文件（有匹配的数据文件存在）。
     */
    private boolean isKnownFlagFile(String name, Set<String> allNames) {
        for (String suffix : KNOWN_FLAG_SUFFIXES) {
            if (name.endsWith(suffix)) {
                String stem = name.substring(0, name.length() - suffix.length());
                // 检查是否有同名（不同扩展名）的数据文件存在
                for (String otherName : allNames) {
                    int dot = otherName.lastIndexOf('.');
                    if (dot > 0 && otherName.substring(0, dot).equals(stem) && !otherName.equals(name)) {
                        return true; // 有数据文件引用此标志 → 是本标志文件
                    }
                }
            }
        }
        return false;
    }

    /**
     * 为指定的数据文件解析期望的标志文件名称。
     *
     * @param flagPattern FLAG 路径模式（如 {@code "{stem}.OK"}）
     * @param fileInfo    数据文件的路径信息
     * @return 解析后的标志文件路径/名称，无法确定时返回 null
     */
    static String resolveFlagName(String flagPattern, ResolvedPath fileInfo) {
        if (flagPattern == null || fileInfo == null) return null;

        // 展开文件衍生变量
        String resolved = flagPattern
                .replace("{stem}", fileInfo.stem() != null ? fileInfo.stem() : "")
                .replace("{name}", fileInfo.name() != null ? fileInfo.name() : "")
                .replace("{ext}", fileInfo.ext() != null ? fileInfo.ext() : "")
                .replace("{dir}", fileInfo.dir() != null ? fileInfo.dir() : "")
                .replace("{dn}", fileInfo.dn() != null ? fileInfo.dn() : "")
                .replace("{up}", fileInfo.up() != null ? fileInfo.up() : "");

        // 相对路径加目录前缀
        if (!resolved.startsWith("/") && fileInfo.dir() != null && !fileInfo.dir().isEmpty()) {
            resolved = fileInfo.dir() + "/" + resolved;
        }

        return resolved;
    }

    /**
     * 从文件路径中提取纯文件名。
     */
    private static String fileNameOnly(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String[] listFiles(String ftpName, String listPattern) throws Exception {
        return transferSupport.executeWithClient(ftpName, client -> {
            log.debug("Multi-directory list pattern: {}", listPattern);
            return client.listFiles(listPattern);
        });
    }

    private String toGlobPattern(String dirPath) {
        if (dirPath == null || dirPath.contains("*")) {
            return dirPath;
        }
        return dirPath.endsWith("/") ? dirPath + "*" : dirPath + "/*";
    }

    // ==================== BATCH 模式：明细表 + 顺序 ====================

    private void handleBatch(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Multi-file upload with details");
        String nodeId = config.getNodeId();
        String ftpName = config.getFtpName();

        List<Map<String, Object>> details = detailRepository.findUploadDetails(
                command.getId(), ColumnNames.STATUS_EMPTY);

        if (details.isEmpty()) {
            log.info("No details found for command: {}", command.getId());
            result.setOutcome(0, UploadSupport.determineMainStatus(
                    new UploadSupport.UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED)), "");
            return;
        }

        boolean truncateFirst = BooleanUtils.isYes(config.getClearTableFlag());

        int totalRecords = 0;
        int successCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        ResolvedPath lastFileInfo = null;
        for (Map<String, Object> detail : details) {
            UploadSupport.UploadResult perFile = processOneDetail(
                    command, config, detail, nodeId, truncateFirst);
            truncateFirst = false; // 仅第一个文件执行 truncate
            totalRecords += perFile.records();
            successCount += perFile.successCount();
            skippedCount += perFile.skippedCount();
            failedCount += perFile.failedCount();

            String fileName = (String) detail.get(ColumnNames.FILE_NAME);
            if (fileName != null && !fileName.isEmpty() && perFile.successCount() > 0) {
                lastFileInfo = resolveDetailFilePath(command, config, detail, fileName);
            }
        }

        // Phase 3 (FTP): postProcess
        ResolvedPath finalLastFileInfo = lastFileInfo;
        int finalTotalRecords = totalRecords;
        try {
            transferSupport.executeWithClient(ftpName, client -> {
                transferSupport.postProcess(client, config, finalLastFileInfo, Map.of("C", String.valueOf(finalTotalRecords)));
                return null;
            });
        } catch (Exception e) {
            log.error("Phase 3 (postProcess) failed for batch upload: {}", e.getMessage(), e);
            result.setOutcome(totalRecords, ColumnNames.STATUS_ERROR,
                    "Data uploaded successfully (" + totalRecords + " records) but post-processing failed: "
                            + e.getMessage());
            return;
        }

        result.setOutcome(totalRecords,
                UploadSupport.determineMainStatus(new UploadSupport.UploadResult(
                        totalRecords, successCount, skippedCount, failedCount, null)), "");
    }

    /**
     * 处理单个明细文件 — 使用 {@link UploadSupport#processSingleFile} 执行完整流程。
     *
     * <p>FTP 借还、上传管线、错误文件迁移均由 processSingleFile 统一处理。
     * 本方法只负责明细表状态更新。
     */
    private UploadSupport.UploadResult processOneDetail(Command command, TransferConfig config,
                                                      Map<String, Object> detail,
                                                      String nodeId, boolean truncateFirst) {
        Long detailId = ((Number) detail.get(ColumnNames.DETAIL_ID)).longValue();
        String fileName = (String) detail.get(ColumnNames.FILE_NAME);

        if (fileName == null || fileName.isEmpty()) {
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_SKIPPED, nodeId);
            return new UploadSupport.UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED);
        }

        // 构建明细上下文（含FIELD_NAME/FIELD_VALUE，供{FIELD_NAME}占位符解析）
        String detailFieldName = (String) detail.get(ColumnNames.FIELD_NAME);
        String detailFieldValue = (String) detail.get(ColumnNames.FIELD_VALUE);
        ResolvedPath fileInfo = resolveDetailFilePath(command, config, detail, fileName);

        Integer detailAuditCount = detail.get(ColumnNames.AUDIT_COUNT) != null
                ? ((Number) detail.get(ColumnNames.AUDIT_COUNT)).intValue() : null;

        // 使用统一的 processSingleFile 处理
        UploadSupport.UploadResult r = support.processSingleFile(
                config.getFtpName(), detailAuditCount, config, fileInfo.fullPath(), detail);

        // 更新明细表状态
        if (ColumnNames.STATUS_SKIPPED.equals(r.status())) {
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_SKIPPED, nodeId);
        } else if (r.status() == null && r.successCount() > 0) {
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_SUCCESS, nodeId);
        } else if (ColumnNames.STATUS_ERROR.equals(r.status())) {
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_ERROR, nodeId);
        }

        return r;
    }

    private ResolvedPath resolveDetailFilePath(Command command, TransferConfig config,
                                              Map<String, Object> detail, String fileName) {
        String detailFieldName = (String) detail.get(ColumnNames.FIELD_NAME);
        String detailFieldValue = (String) detail.get(ColumnNames.FIELD_VALUE);
        Map<String, String> detailContext = transferSupport.buildContext(
                command, detailFieldName, detailFieldValue);
        return transferSupport.resolveFilePath(
                config.getFilePath() + "/" + fileName, detailContext);
    }

    // ==================== 通用工具 ====================

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                log.warn("Upload executor did not terminate within 1 hour, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void rollbackOnFailure(TransferConfig config, int failedCount) throws TransferException {
        if (BooleanUtils.isYes(config.getClearTableFlag())) {
            support.truncateTable(config);
            log.error("Rolled back table {} due to {} failed file(s) post-audit",
                    config.getTableName(), failedCount);
        } else {
            log.error("Post-audit failed for {} file(s); incremental mode, preserving existing data in {}.{}",
                    failedCount, config.getDbName(), config.getTableName());
        }
        throw new TransferException("POST_AUDIT_FAILED",
                "Post-audit failed for " + failedCount + " file(s)");
    }

    /**
     * SERIAL 模式每个文件的处理任务。
     * 使用 {@link UploadSupport#processSingleFile} 执行完整流程。
     */
    private class FileTask implements Callable<Integer> {

        private final String ftpName;
        private final String filePath;
        private final Command command;
        private final TransferConfig config;

        FileTask(String ftpName, String filePath, Command command, TransferConfig config) {
            this.ftpName = ftpName;
            this.filePath = filePath;
            this.command = command;
            this.config = config;
        }

        @Override
        public Integer call() {
            UploadSupport.UploadResult r = support.processSingleFile(
                    ftpName, command.getAuditCount(), config, filePath, null);
            if (ColumnNames.STATUS_SKIPPED.equals(r.status())) {
                log.warn("Skipped file (flag not found): {}", filePath);
                return TASK_SKIP;
            }
            if (r.status() == null) {
                log.info("Uploaded file: {} ({} records)", filePath, r.records());
                return r.records();
            }
            log.error("Upload failed for {}: {} records, status={}", filePath, r.records(), r.status());
            return TASK_FAIL;
        }
    }
}
