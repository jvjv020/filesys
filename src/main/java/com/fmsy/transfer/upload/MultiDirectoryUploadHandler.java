package com.fmsy.transfer.upload;

import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.exception.TransferException;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
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
 * UPLOAD_MULTI + SERIAL 场景:通配符匹配目录所有文件,并行上传到同一张表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiDirectoryUploadHandler implements TransferHandler {

    private final FieldMappingBuilder fieldMappingBuilder;
    private final UploadSupport support;
    private final TransferSupport transferSupport;
    private final FtpPool ftpPool;
    private final IntFunction<ExecutorService> batchExecutorFactory;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
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
                futures.add(executor.submit(new FileTask(filePath)));
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

        if (failedCount > 0 && config.getEmptyDataHandling() == EmptyDataHandling.ERROR) {
            if (BooleanUtils.isYes(config.getClearTableFlag())) {
                try {
                    support.truncateTable(config);
                    log.error("Rolled back table {} due to {} failed file(s) post-audit",
                            config.getTableName(), failedCount);
                } catch (Exception ex) {
                    log.error("Failed to roll back table {}: {}",
                            config.getTableName(), ex.getMessage());
                }
            } else {
                log.error("Post-audit failed for {} file(s); incremental mode, preserving existing data in {}.{}",
                        failedCount, config.getDbName(), config.getTableName());
            }
            throw new TransferException("POST_AUDIT_FAILED",
                    "Post-audit failed for " + failedCount + " file(s)");
        }

        // Phase 3: postProcess
        Map<String, String> extra = new HashMap<>();
        extra.put("C", String.valueOf(totalRecords));
        transferSupport.executeWithClient(ftpName, client -> {
            transferSupport.postProcess(client, config, dirInfo, extra);
            return null;
        });

        UploadSupport.UploadResult ur = new UploadSupport.UploadResult(totalRecords, successCount, skippedCount, failedCount);
        result.setOutcome(ur.records(), UploadSupport.determineMainStatus(ur), "");
    }

    /**
     * 列出匹配的文件（短生命周期 FTP 连接）。
     * @return 文件列表数组，preCheck 失败时返回 null
     */
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

    /**
     * 路径 → 列表通配符:无 * 时自动追加 /*;以 / 结尾时只补 *。
     */
    private String toGlobPattern(String dirPath) {
        if (dirPath == null || dirPath.contains("*")) {
            return dirPath;
        }
        return dirPath.endsWith("/") ? dirPath + "*" : dirPath + "/*";
    }

    @RequiredArgsConstructor
    private static class FileTask implements Callable<Integer> {
        static final int SKIP = -1;
        static final int FAIL = -2;

        private final String filePath;

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
