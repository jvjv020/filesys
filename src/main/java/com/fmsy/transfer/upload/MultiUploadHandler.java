package com.fmsy.transfer.upload;

import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.exception.TransferException;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.BooleanUtils;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * UPLOAD_MULTI 场景:按指令类型分流。
 *
 * <ul>
 *   <li>{@link CommandType#SERIAL}(null) — 通配符匹配目录所有文件,并行上传到同一张表</li>
 *   <li>{@link CommandType#BATCH}('R') — 按明细表逐文件上传(顺序执行)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiUploadHandler implements TransferHandler {

    private final DetailRepository detailRepository;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final UploadSupport support;
    private final TransferSupport transferSupport;
    private final FtpPool ftpPool;
    private final IntFunction<ExecutorService> batchExecutorFactory;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        if (command.getCommandType() == CommandType.BATCH) {
            handleBatch(command, config, result);
        } else {
            handleSerial(command, config, result);
        }
    }

    // ==================== SERIAL 模式：目录通配符 + 并发 ====================

    private void handleSerial(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Multi-file upload from directory");
        String ftpName = config.getFtpName();

        ResolvedPath dirInfo = transferSupport.resolveFilePath(config.getFilePath(), command);
        String listPattern = toGlobPattern(dirInfo != null ? dirInfo.fullPath() : null);

        // Phase 1: preCheck + listFiles
        String[] files = listFiles(ftpName, config, dirInfo, listPattern);
        if (files == null) {
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed");
            return;
        }
        if (files.length == 0) {
            log.info("No files found in directory: {} (pattern: {})", dirInfo, listPattern);
            result.setOutcome(0, UploadSupport.determineMainStatus(UploadSupport.UploadResult.allSkipped()), "");
            return;
        }
        log.info("Found {} files in directory: {} (pattern: {})", files.length, dirInfo, listPattern);

        // Phase 2: DB truncate + parallel file upload
        if (BooleanUtils.isYes(config.getClearTableFlag())) {
            support.truncateTable(config);
        }

        int concurrency = config.getConcurrency() != null ? config.getConcurrency() : 3;
        ExecutorService executor = batchExecutorFactory.apply(concurrency);

        int totalRecords = 0;
        int successCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String filePath : files) {
                futures.add(executor.submit(new FileTask(filePath, ftpName, command, config)));
            }

            for (int i = 0; i < futures.size(); i++) {
                try {
                    Integer taskResult = futures.get(i).get(30, TimeUnit.MINUTES);
                    if (taskResult != null && taskResult > 0) {
                        totalRecords += taskResult;
                        successCount++;
                    } else if (taskResult != null && taskResult == FileTask.SKIP) {
                        skippedCount++;
                    } else {
                        failedCount++;
                    }
                } catch (Exception e) {
                    log.error("Failed to upload file: {}", files[i], e);
                    failedCount++;
                }
            }
        } finally {
            shutdownExecutor(executor);
        }

        if (failedCount > 0 && config.getEmptyDataHandling() == EmptyDataHandling.ERROR) {
            rollbackOnFailure(config, failedCount);
        }

        // Phase 3: postProcess
        Map<String, String> extra = new HashMap<>();
        extra.put("C", String.valueOf(totalRecords));
        transferSupport.executeWithClient(ftpName, client -> {
            transferSupport.postProcess(client, config, dirInfo, extra);
            return null;
        });

        result.setOutcome(totalRecords,
                UploadSupport.determineMainStatus(new UploadSupport.UploadResult(
                        totalRecords, successCount, skippedCount, failedCount)), "");
    }

    private String[] listFiles(String ftpName, TransferConfig config,
                               ResolvedPath dirInfo, String listPattern) throws Exception {
        return transferSupport.executeWithClient(ftpName, client -> {
            if (!transferSupport.preCheck(client, config, dirInfo)) {
                return null;
            }
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

        // Phase 1 (FTP): preCheck → 释放连接
        boolean preCheckOk = transferSupport.executeWithClient(ftpName, client ->
                transferSupport.preCheck(client, config, null));
        if (!preCheckOk) {
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed");
            return;
        }

        // Phase 2 (DB + per-file FTP): 查询明细表,逐文件处理
        // 每个文件内部独立借还 FTP 连接,DB 操作不持有 FTP 连接
        List<Map<String, Object>> details = detailRepository.findUploadDetails(
                command.getId(), ColumnNames.STATUS_EMPTY);

        if (details.isEmpty()) {
            log.info("No details found for command: {}", command.getId());
            result.setOutcome(0, UploadSupport.determineMainStatus(UploadSupport.UploadResult.allSkipped()), "");
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
        Map<String, String> extra = new HashMap<>();
        extra.put("C", String.valueOf(totalRecords));
        transferSupport.executeWithClient(ftpName, client -> {
            transferSupport.postProcess(client, config, lastFileInfo, extra);
            return null;
        });

        result.setOutcome(totalRecords,
                UploadSupport.determineMainStatus(new UploadSupport.UploadResult(
                        totalRecords, successCount, skippedCount, failedCount)), "");
    }

    private UploadSupport.UploadResult processOneDetail(Command command, TransferConfig config,
                                                        Map<String, Object> detail,
                                                        String nodeId, boolean truncateFirst) {
        Long detailId = ((Number) detail.get(ColumnNames.DETAIL_ID)).longValue();
        String fileName = (String) detail.get(ColumnNames.FILE_NAME);

        if (fileName == null || fileName.isEmpty()) {
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_SKIPPED, nodeId);
            return new UploadSupport.UploadResult(0, 0, 1, 0);
        }

        ResolvedPath fileInfo = resolveDetailFilePath(command, config, detail, fileName);

        Integer detailAuditCount = detail.get(ColumnNames.AUDIT_COUNT) != null
                ? ((Number) detail.get(ColumnNames.AUDIT_COUNT)).intValue() : -1;
        FileConverter converter = ConverterFactory.get(config.getParserType());
        int fileLineCount = support.preAudit(detailAuditCount, config, fileInfo.fullPath(), converter);
        if (fileLineCount < 0) {
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_ERROR, nodeId);
            return new UploadSupport.UploadResult(0, 0, 0, 1);
        }

        return transferSupport.executeWithClient(config.getFtpName(), client -> {
            try (InputStream is = client.getInputStream(fileInfo.fullPath())) {
                FieldMapping mapping = fieldMappingBuilder.buildForUpload(config, detail);

                try (CloseableIterator<List<Map<String, Object>>> dataIter =
                        new CloseableIterator<>(converter.parse(is, mapping))) {
                    int count = support.insertBatchInTx(config, dataIter, mapping, truncateFirst);
                    int actualFileRecords = dataIter.getRecordCount();
                    boolean postAuditOk = support.postAudit(config, actualFileRecords, count);
                    if (postAuditOk) {
                        detailRepository.updateStatus(detailId, ColumnNames.STATUS_SUCCESS, nodeId);
                        return new UploadSupport.UploadResult(count, 1, 0, 0);
                    } else {
                        detailRepository.updateStatus(detailId, ColumnNames.STATUS_ERROR, nodeId);
                        return new UploadSupport.UploadResult(0, 0, 0, 1);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to upload file from detail: {}", fileInfo.fullPath(), e);
                detailRepository.updateStatus(detailId, ColumnNames.STATUS_ERROR, nodeId);
                return new UploadSupport.UploadResult(0, 0, 0, 1);
            }
        });
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
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
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

    private class FileTask implements Callable<Integer> {
        static final int SKIP = -1;
        static final int FAIL = -2;

        private final String filePath;
        private final String ftpName;
        private final Command command;
        private final TransferConfig config;

        FileTask(String filePath, String ftpName, Command command, TransferConfig config) {
            this.filePath = filePath;
            this.ftpName = ftpName;
            this.command = command;
            this.config = config;
        }

        @Override
        public Integer call() {
            FtpClient client = ftpPool.getClient(ftpName);
            try {
                ResolvedPath fileInfo = ResolvedPath.of(filePath);
                if (!transferSupport.preCheck(client, config, fileInfo)) {
                    log.warn("Pre-check failed for file: {}", filePath);
                    return SKIP;
                }

                FileConverter converter = ConverterFactory.get(config.getParserType());
                int fileLineCount = support.preAudit(command.getAuditCount(), config, filePath, converter);
                if (fileLineCount < 0) {
                    log.warn("Pre-audit failed for file: {}", filePath);
                    return FAIL;
                }

                try (InputStream is = client.getInputStream(filePath)) {
                    FieldMapping mapping = fieldMappingBuilder.buildForUpload(config, null);

                    try (CloseableIterator<List<Map<String, Object>>> dataIter =
                            new CloseableIterator<>(converter.parse(is, mapping))) {
                        int count = support.insertBatchInTx(config, dataIter, mapping);
                        int actualFileRecords = dataIter.getRecordCount();

                        if (!support.postAudit(config, actualFileRecords, count)) {
                            if (config.getEmptyDataHandling() == EmptyDataHandling.ERROR) {
                                throw new TransferException("POST_AUDIT_FAILED",
                                        "Post-audit failed for file: " + filePath);
                            }
                            // 非 ERROR 模式：数据已插入但审计不通过。返回 count 让 totalRecords 计入，
                            // 但主线程识别为失败（status=ERROR）。与 SingleUploadHandler 行为一致。
                            log.warn("Post-audit failed for file: {} (records={}), data kept in table",
                                    filePath, count);
                            return count;
                        }

                        log.info("Uploaded file: {}", filePath);
                        return count;
                    }
                }
            } catch (Exception e) {
                log.error("Failed to upload file: {}", filePath, e);
                return FAIL;
            } finally {
                client.close();
            }
        }
    }
}
