package com.fmsy.transfer.download;

import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.FilePathUtils;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * DOWNLOAD_SINGLE_NODE 场景:按拆分字段分桶,每个桶生成一个文件,并行下载到同一节点 FTP。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleNodeDownloadHandler implements TransferHandler {

    private final FtpPool ftpPool;
    private final BucketDistributor bucketDistributor;
    private final TargetTableRepository targetTableRepository;
    private final FlagFileService flagFileService;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final IntFunction<ExecutorService> batchExecutorFactory;
    private final DownloadSupport support;
    private final TransferSupport transferSupport;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Single node multi-file download");
        String nodeId = config.getNodeId();
        CommandType commandType = command.getCommandType();

        // ===== Phase 1: splitFields + 顶层 preCheck =====
        String splitFields = config.getSplitFields();
        if (splitFields == null || splitFields.isEmpty()) {
            log.warn("No split fields configured for DOWNLOAD_SINGLE_NODE");
            result.setOutcome(0, DownloadSupport.determineMainStatus(true, 0, 0), "");
            return;
        }
        String postOps = config.getPostOperations();

        ResolvedPath baseFileInfo;
        baseFileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);
        boolean preCheckOk = transferSupport.executeWithClient(config.getFtpName(), client -> {
            if (!transferSupport.preCheck(client, config, baseFileInfo)) {
                result.setOutcome(0, DownloadSupport.determineMainStatus(false, 0, 1), "Pre-check failed");
                return false;
            }
            String parentDir = FilePathUtils.extractParentDirectory(baseFileInfo.fullPath());
            if (parentDir != null && !parentDir.isEmpty()) {
                client.mkdirs(parentDir);
            }
            return true;
        });
        if (!preCheckOk) return;

        // ===== Phase 2: SERIAL 模式顶层 pre-audit =====
        if (commandType != CommandType.BATCH && command.getAuditCount() != null && command.getAuditCount() >= 0) {
            if (!support.preAudit(config, command.getAuditCount())) {
                result.setOutcome(0, DownloadSupport.determineMainStatus(false, 0, 1), "Pre-audit failed");
                return;
            }
        }

        // ===== Phase 3: 分桶 =====
        List<Detail> buckets = new ArrayList<>();
        if (commandType == CommandType.BATCH) {
            buckets = bucketDistributor.getBuckets(command.getId(), Integer.MAX_VALUE);
            if (buckets.isEmpty()) {
                log.info("No buckets found for BATCH command: {}", command.getId());
                result.setOutcome(0, DownloadSupport.determineMainStatus(true, 0, 0), "");
                return;
            }
        } else {
            for (String fieldValue : bucketDistributor.distinctBuckets(config)) {
                Detail bucket = new Detail();
                bucket.setFieldValue(fieldValue);
                buckets.add(bucket);
            }
        }

        // ===== Phase 4: 并行桶处理 =====
        AtomicInteger totalRecordCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        AtomicBoolean allFilesSuccess = new AtomicBoolean(true);
        ConcurrentLinkedQueue<String> generatedFiles = new ConcurrentLinkedQueue<>();

        ExecutorService bucketExecutor = batchExecutorFactory.apply(Math.max(1, buckets.size()));
        try {
            for (Detail bucket : buckets) {
                bucketExecutor.execute(() -> processBucketParallel(
                        bucket, command, config, nodeId, baseFileInfo, postOps,
                        totalRecordCount, failedCount, skippedCount, allFilesSuccess, generatedFiles));
            }
            bucketExecutor.shutdown();
            if (!bucketExecutor.awaitTermination(1, TimeUnit.HOURS)) {
                log.error("Bucket processing timed out for command: {}", command.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Bucket processing interrupted for command: {}", command.getId());
        } finally {
            if (!bucketExecutor.isTerminated()) {
                bucketExecutor.shutdownNow();
            }
        }

        // ===== Phase 5: 总标志文件(仅在所有桶成功时生成) =====
        if (allFilesSuccess.get() && postOps != null && postOps.contains("TOTAL")) {
            transferSupport.executeWithClient(config.getFtpName(), postClient -> {
                String totalFlagOps = FlagFileService.filterOpsByType(postOps, "TOTAL");
                flagFileService.process(postClient, totalFlagOps, baseFileInfo, null);
                return null;
            });
        }

        if (command.getAuditCount() != null && command.getAuditCount() >= 0) {
            boolean postAuditOk = support.postAudit(config, baseFileInfo.fullPath());
            if (!postAuditOk) {
                log.error("Post-audit failed for command {}, rolling back {} generated file(s)",
                        command.getId(), generatedFiles.size());
                transferSupport.executeWithClient(config.getFtpName(), client -> {
                    for (String f : generatedFiles) {
                        DownloadSupport.rollbackAfterPostAuditFailure(client, f,
                                "single-node download post-audit");
                    }
                    return null;
                });
                allFilesSuccess.set(false);
                failedCount.incrementAndGet();
            }
        }

        DownloadSupport.BucketSummary summary = new DownloadSupport.BucketSummary(
                totalRecordCount.get(), allFilesSuccess.get(), failedCount.get(), skippedCount.get());
        result.setOutcome(summary.totalRecords(), DownloadSupport.determineMainStatus(summary), "");
    }

    /**
     * 并行处理单个桶(bucketExecutor 线程池线程中执行)
     */
    private void processBucketParallel(Detail bucket, Command command, TransferConfig config,
                                        String nodeId, ResolvedPath baseFileInfo, String postOps,
                                        AtomicInteger totalRecordCount, AtomicInteger failedCount,
                                        AtomicInteger skippedCount, AtomicBoolean allFilesSuccess,
                                        ConcurrentLinkedQueue<String> generatedFiles) {
        String fieldValue = bucket.getFieldValue();
        Map<String, String> context = transferSupport.buildContext(
                null, config.getSplitFields(), fieldValue);
        // baseFileInfo.fullPath() 已经包含日期等占位符的解析结果，
        // 这里只需用分桶字段值做二次替换，产出每个桶的目标路径
        ResolvedPath targetFileInfo = transferSupport.resolveFilePath(
                baseFileInfo.fullPath(), context);

        // Phase 1: DB-only checks (no FTP client held)
        // 优先从 preAudit 获取计数，避免二次 countByBucket
        int recordCount = -1;
        if (command.getCommandType() == CommandType.BATCH) {
            Integer detailAuditCount = bucket.getAuditCount();
            if (detailAuditCount != null && detailAuditCount >= 0) {
                recordCount = support.preAuditByBucket(config, detailAuditCount, fieldValue);
                if (recordCount < 0) {
                    log.error("Pre-audit failed for bucket value: {}", fieldValue);
                    allFilesSuccess.set(false);
                    failedCount.incrementAndGet();
                    support.updateDetailStatusForBucket(bucket, ColumnNames.STATUS_ERROR, nodeId);
                    return;
                }
            }
        }
        if (recordCount < 0) {
            recordCount = targetTableRepository.countByBucket(
                    config.getDbName(), config.getTableName(), config.getSplitFields(), fieldValue);
        }

        EmptyDataHandling emptyHandling = config.getEmptyDataHandling();
        if (!transferSupport.handleEmptyData(recordCount, emptyHandling)) {
            // handleEmptyData 仅在 ERROR 或 SKIP 时返回 false，else 分支不可达
            if (emptyHandling == EmptyDataHandling.ERROR) {
                allFilesSuccess.set(false);
                failedCount.incrementAndGet();
                support.updateDetailStatusForBucket(bucket, ColumnNames.STATUS_ERROR, nodeId);
            } else { // SKIP
                allFilesSuccess.set(false);
                skippedCount.incrementAndGet();
                support.updateDetailStatusForBucket(bucket, ColumnNames.STATUS_SKIPPED, nodeId);
            }
            return;
        }

        // Phase 2: FTP operations (borrow client just before use)
        FtpClient client = ftpPool.getClient(config.getFtpName());
        try {
            FileConverter converter = ConverterFactory.get(config.getParserType());
            FieldMapping mapping = fieldMappingBuilder.buildForDownload(config);
            try (var data = targetTableRepository.streamBucketData(
                    config.getDbName(), config.getTableName(), config.getSplitFields(), fieldValue);
                 OutputStream os = client.getOutputStream(targetFileInfo.fullPath())) {
                converter.generate(os, data, mapping);
                client.completePendingCommand();
            } catch (IOException e) {
                log.error("Failed to generate file for bucket {}: {}", fieldValue, e.getMessage(), e);
                allFilesSuccess.set(false);
                failedCount.incrementAndGet();
                support.updateDetailStatusForBucket(bucket, ColumnNames.STATUS_ERROR, nodeId);
                return;
            }
            generatedFiles.add(targetFileInfo.fullPath());

            // 每桶 sub-flag
            Map<String, String> extra = new HashMap<>();
            extra.put("C", String.valueOf(recordCount));
            String subFlagOnly = FlagFileService.filterOpsByType(postOps, "SUB");
            flagFileService.process(client, subFlagOnly, targetFileInfo, extra);

            support.updateDetailStatusForBucket(bucket, ColumnNames.STATUS_SUCCESS, nodeId);
            log.info("Downloaded file for bucket value: {}", fieldValue);
            totalRecordCount.addAndGet(recordCount);
        } catch (Exception e) {
            log.error("Bucket processing crashed: {}", fieldValue, e);
            allFilesSuccess.set(false);
            failedCount.incrementAndGet();
            support.updateDetailStatusForBucket(bucket, ColumnNames.STATUS_ERROR, nodeId);
        } finally {
            client.close();
        }
    }
}
