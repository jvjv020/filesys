package com.fmsy.transfer.download;

import com.fmsy.enums.CommandType;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
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

import static com.fmsy.transfer.TransferSupport.determineMainStatus;

/**
 * DOWNLOAD_SINGLE_NODE 场景:按拆分字段分桶,每个桶生成一个文件,并行下载到同一节点 FTP。
 *
 * <p>桶的并行处理通过线程池提交,每桶走 {@link DownloadSupport#executePipeline} 管线。
 * Handler 负责顶层 preCheck、分桶、总标志文件和聚合后稽核。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleNodeDownloadHandler implements TransferHandler {

    private final BucketDistributor bucketDistributor;
    private final TransferSupport transferSupport;
    private final DownloadSupport downloadSupport;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final IntFunction<ExecutorService> batchExecutorFactory;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Single node multi-file download, command={}, table={}",
                command.getId(), config.getTableName());
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
        boolean preCheckOk = transferSupport.executeWithClient(config.getFtpName(), client ->
                downloadSupport.preCheckAndMkdirs(client, config, baseFileInfo, baseFileInfo.fullPath()));
        if (!preCheckOk) {
            result.setOutcome(0, determineMainStatus(false, 0, 1), "Pre-check failed");
            return;
        }

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

        // ===== Phase 4: 并行桶处理 =====
        FieldMapping sharedMapping = fieldMappingBuilder.buildForDownload(config);
        int concurrency = config.getConcurrency() != null ? config.getConcurrency() : 3;
        BucketBatchResult br = processBucketsInParallel(buckets, config, baseFileInfo,
                splitFields, sharedMapping, concurrency, commandType, config.getNodeId());

        // ===== Phase 5: 总标志文件(仅在所有桶成功时生成) =====
        if (br.isAllSuccess() && postOps != null && postOps.contains("TOTAL")) {
            String totalFlagOps = FlagFileService.filterOpsByType(postOps, "TOTAL");
            if (totalFlagOps != null) {
                transferSupport.executeWithClient(config.getFtpName(), postClient -> {
                    transferSupport.postProcess(postClient, totalFlagOps, baseFileInfo, null);
                    return null;
                });
            }
        }

        // ===== Phase 6: 聚合后稽核 =====
        if (command.getAuditCount() != null && command.getAuditCount() >= 0) {
            boolean postAuditOk = downloadSupport.postAudit(config, baseFileInfo.fullPath());
            if (!postAuditOk) {
                log.error("Post-audit failed for command {}, rolling back {} generated file(s)",
                        command.getId(), br.getGeneratedFiles().size());
                transferSupport.executeWithClient(config.getFtpName(), client -> {
                    for (String f : br.getGeneratedFiles()) {
                        DownloadSupport.rollbackAfterPostAuditFailure(client, f,
                                "single-node download post-audit");
                    }
                    return null;
                });
                br.forceFailure();
            }
        }

        result.setOutcome(br.getTotalRecordCount(),
                br.determineStatus(), "");
    }

    /**
     * 并行处理所有桶:每桶走 DownloadSupport.executePipeline 管线,结果聚合到 BucketBatchResult。
     */
    private BucketBatchResult processBucketsInParallel(List<Detail> buckets, TransferConfig config,
                                                        ResolvedPath baseFileInfo, String splitFields,
                                                        FieldMapping sharedMapping, int concurrency,
                                                        CommandType commandType, String nodeId) {
        BucketBatchResult result = new BucketBatchResult();
        if (buckets == null || buckets.isEmpty()) return result;

        String ftpName = config.getFtpName();
        int threadCount = Math.max(1, Math.min(buckets.size(), concurrency));
        ExecutorService executor = batchExecutorFactory.apply(threadCount);
        try {
            for (Detail bucket : buckets) {
                executor.execute(() ->
                        processOneBucket(bucket, config, baseFileInfo, splitFields,
                                sharedMapping, commandType, nodeId, result, ftpName));
            }
        } finally {
            TransferSupport.shutdownExecutor(executor, 1, TimeUnit.HOURS, "SingleNodeDownloadHandler");
        }
        return result;
    }

    /** 处理单个桶: 路径解析 → 构建管线选项 → executePipeline → 聚合结果 */
    private void processOneBucket(Detail bucket, TransferConfig config,
                                  ResolvedPath baseFileInfo, String splitFields,
                                  FieldMapping sharedMapping, CommandType commandType,
                                  String nodeId, BucketBatchResult result, String ftpName) {
        try {
            // 路径解析
            Map<String, String> context = transferSupport.buildContext(
                    null, splitFields, bucket.getFieldValue());
            ResolvedPath targetFileInfo = transferSupport.resolveFilePath(
                    baseFileInfo.fullPath(), context);

            // 构建管线选项
            DownloadSupport.PipelineOptions pipelineOpts = DownloadSupport.PipelineOptions.builder()
                    .wholeTable(false)
                    .fieldValue(bucket.getFieldValue())
                    .expectedAuditCount(null)
                    .enablePreCheck(false)       // 顶层已做
                    .enableOverwriteCheck(false)
                    .enablePostAudit(false)       // 聚合层做
                    .postOpsFilter("SUB")
                    .detail(bucket)
                    .nodeId(nodeId)
                    .build();

            // 复用预构建的字段映射
            pipelineOpts.setFieldMapping(sharedMapping);

            // BATCH 模式:用明细表的 auditCount
            if (commandType == CommandType.BATCH) {
                Integer detailAudit = bucket.getAuditCount();
                if (detailAudit != null && detailAudit >= 0) {
                    pipelineOpts.setExpectedAuditCount(detailAudit);
                }
            }

            // 执行管线
            DownloadSupport.PipelineResult pr = downloadSupport.executePipeline(
                    ftpName, config, targetFileInfo, pipelineOpts);

            // 聚合结果
            result.accumulate(pr, targetFileInfo != null ? targetFileInfo.fullPath() : null);

        } catch (Exception e) {
            log.error("Bucket {} processing failed for table {}: {}", bucket.getId(), config.getTableName(), e.getMessage(), e);
            result.accumulateFailure();
        }
    }

    // ==================== 桶聚合结果(线程安全) ====================

    /**
     * 桶处理聚合结果 — 线程安全。{@link #processBucketsInParallel} 返回后读取各计数即可。
     */
    static class BucketBatchResult {
        private final AtomicInteger totalRecordCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger failedCount = new AtomicInteger(0);
        private final AtomicInteger skippedCount = new AtomicInteger(0);
        private final AtomicBoolean allSuccess = new AtomicBoolean(true);
        private final ConcurrentLinkedQueue<String> generatedFiles = new ConcurrentLinkedQueue<>();

        void accumulate(DownloadSupport.PipelineResult pr, String filePath) {
            if (pr.isSuccess()) {
                totalRecordCount.addAndGet(pr.getRecordCount());
                successCount.incrementAndGet();
                if (filePath != null) {
                    generatedFiles.add(filePath);
                }
            } else {
                allSuccess.set(false);
                if (ColumnNames.STATUS_SKIPPED.equals(pr.getStatus())) {
                    skippedCount.incrementAndGet();
                } else {
                    failedCount.incrementAndGet();
                }
            }
        }

        void accumulateFailure() {
            allSuccess.set(false);
            failedCount.incrementAndGet();
        }

        /** 强制标记为失败(聚合后稽核失败时由调用方调用) */
        public void forceFailure() {
            allSuccess.set(false);
            failedCount.incrementAndGet();
        }

        /** 根据聚合结果判定最终状态: 全成功→Y, 有失败→E, 仅跳过→N */
        public String determineStatus() {
            if (allSuccess.get()) return ColumnNames.STATUS_SUCCESS;
            if (failedCount.get() > 0) return ColumnNames.STATUS_ERROR;
            if (skippedCount.get() > 0) return ColumnNames.STATUS_SKIPPED;
            return ColumnNames.STATUS_ERROR;
        }

        public int getTotalRecordCount() { return totalRecordCount.get(); }
        public int getSuccessCount() { return successCount.get(); }
        public int getFailedCount() { return failedCount.get(); }
        public int getSkippedCount() { return skippedCount.get(); }
        public boolean isAllSuccess() { return allSuccess.get(); }
        public List<String> getGeneratedFiles() {
            return List.copyOf(generatedFiles);
        }
    }
}