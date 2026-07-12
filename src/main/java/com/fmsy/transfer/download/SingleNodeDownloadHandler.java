package com.fmsy.transfer.download;

import com.fmsy.enums.CommandType;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import static com.fmsy.transfer.TransferSupport.determineMainStatus;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.FilePathUtils;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 *
 * <p>桶的并行处理委托 {@link DownloadSupport#executePipeline} 执行单文件管线,
 * Handler 负责顶层 preCheck、分桶、总标志文件和聚合后稽核。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleNodeDownloadHandler implements TransferHandler {

    private final DownloadSupport downloadSupport;
    private final BucketDistributor bucketDistributor;
    private final TransferSupport transferSupport;
    private final IntFunction<ExecutorService> batchExecutorFactory;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Single node multi-file download");
        CommandType commandType = command.getCommandType();

        // ===== Phase 1: splitFields + 顶层 preCheck =====
        String splitFields = config.getSplitFields();
        if (splitFields == null || splitFields.isEmpty()) {
            log.warn("No split fields configured for DOWNLOAD_SINGLE_NODE");
            result.setOutcome(0, determineMainStatus(true, 0, 0), "");
            return;
        }
        String postOps = config.getPostOperations();

        ResolvedPath baseFileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);
        boolean preCheckOk = transferSupport.executeWithClient(config.getFtpName(), client -> {
            if (!transferSupport.preCheck(client, config, baseFileInfo)) {
                result.setOutcome(0, determineMainStatus(false, 0, 1), "Pre-check failed");
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
            int auditResult = downloadSupport.preAudit(config, command.getAuditCount());
            if (auditResult < 0) {
                result.setOutcome(0, determineMainStatus(false, 0, 1), "Pre-audit failed");
                return;
            }
        }

        // ===== Phase 3: 分桶 =====
        List<Detail> buckets = new ArrayList<>();
        if (commandType == CommandType.BATCH) {
            buckets = bucketDistributor.getBuckets(command.getId(), Integer.MAX_VALUE);
            if (buckets.isEmpty()) {
                log.info("No buckets found for BATCH command: {}", command.getId());
                result.setOutcome(0, determineMainStatus(true, 0, 0), "");
                return;
            }
        } else {
            for (String fieldValue : bucketDistributor.distinctBuckets(config)) {
                Detail bucket = new Detail();
                bucket.setFieldValue(fieldValue);
                buckets.add(bucket);
            }
        }

        // ===== Phase 4: 并行桶处理(每桶通过管线执行) =====
        BucketResult bucketResult = new BucketResult();
        ConcurrentLinkedQueue<String> generatedFiles = new ConcurrentLinkedQueue<>();

        int concurrency = config.getConcurrency() != null ? config.getConcurrency() : 3;
        ExecutorService bucketExecutor = batchExecutorFactory.apply(
                Math.max(1, Math.min(buckets.size(), concurrency)));
        try {
            for (Detail bucket : buckets) {
                bucketExecutor.execute(() -> processBucketParallel(
                        bucket, command, config, baseFileInfo,
                        bucketResult, generatedFiles));
            }
        } finally {
            shutdownExecutor(bucketExecutor, command.getId());
        }

        // ===== Phase 5: 总标志文件(仅在所有桶成功时生成) =====
        if (bucketResult.allSuccess.get() && postOps != null && postOps.contains("TOTAL")) {
            String totalFlagOps = FlagFileService.filterOpsByType(postOps, "TOTAL");
            if (totalFlagOps != null) {
                transferSupport.executeWithClient(config.getFtpName(), postClient -> {
                    transferSupport.postProcess(postClient, totalFlagOps, baseFileInfo);
                    return null;
                });
            }
        }

        // ===== Phase 6: 聚合后稽核 =====
        if (command.getAuditCount() != null && command.getAuditCount() >= 0) {
            boolean postAuditOk = downloadSupport.postAudit(config, baseFileInfo.fullPath());
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
                bucketResult.allSuccess.set(false);
                bucketResult.failedCount.incrementAndGet();
            }
        }

        result.setOutcome(bucketResult.totalRecordCount.get(),
                determineMainStatus(bucketResult.allSuccess.get(),
                        bucketResult.failedCount.get(), bucketResult.skippedCount.get()), "");
    }

    private static void shutdownExecutor(ExecutorService executor, Long commandId) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                log.error("Bucket processing timed out for command: {}", commandId);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static class BucketResult {
        final AtomicInteger totalRecordCount = new AtomicInteger(0);
        final AtomicInteger failedCount = new AtomicInteger(0);
        final AtomicInteger skippedCount = new AtomicInteger(0);
        final AtomicBoolean allSuccess = new AtomicBoolean(true);
    }

    /**
     * 并行处理单个桶 — 委托 {@link DownloadSupport#executePipeline} 执行单文件下载管线。
     */
    private void processBucketParallel(Detail bucket, Command command, TransferConfig config,
                                        ResolvedPath baseFileInfo,
                                        BucketResult result, ConcurrentLinkedQueue<String> generatedFiles) {
        String fieldValue = bucket.getFieldValue();
        Map<String, String> context = transferSupport.buildContext(
                null, config.getSplitFields(), fieldValue);
        ResolvedPath targetFileInfo = transferSupport.resolveFilePath(
                baseFileInfo.fullPath(), context);

        // 确定预期稽核数:BATCH 模式用明细表的 auditCount,SERIAL 模式无每桶稽核
        Integer auditCount = null;
        if (command.getCommandType() == CommandType.BATCH) {
            Integer detailAudit = bucket.getAuditCount();
            if (detailAudit != null && detailAudit >= 0) {
                auditCount = detailAudit;
            }
        }

        DownloadSupport.PipelineOptions opts = DownloadSupport.PipelineOptions.builder()
                .wholeTable(false)
                .fieldValue(fieldValue)
                .expectedAuditCount(auditCount)
                .enablePreCheck(false)       // 顶层已做
                .enableOverwriteCheck(false)
                .enablePostAudit(false)      // 聚合层做
                .postOpsFilter("SUB")       // 仅生成子标志文件
                .detail(bucket)
                .nodeId(config.getNodeId())
                .build();

        DownloadSupport.PipelineResult pr = downloadSupport.executePipeline(
                config.getFtpName(), config, targetFileInfo, opts);

        if (pr.isSuccess()) {
            generatedFiles.add(targetFileInfo.fullPath());
            result.totalRecordCount.addAndGet(pr.getRecordCount());
        } else {
            result.allSuccess.set(false);
            if (ColumnNames.STATUS_SKIPPED.equals(pr.getStatus())) {
                result.skippedCount.incrementAndGet();
            } else {
                result.failedCount.incrementAndGet();
            }
        }
    }
}
