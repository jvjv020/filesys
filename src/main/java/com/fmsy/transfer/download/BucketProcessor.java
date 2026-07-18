package com.fmsy.transfer.download;

import com.fmsy.model.Detail;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.transfer.TransferUtils;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * 桶处理器 — 将桶列表并行派发到 {@link DownloadSupport#executePipeline} 并聚合结果。
 *
 * <p>抽取自 {@link SingleNodeDownloadHandler} 和 {@link com.fmsy.transfer.download.ChildBucketProcessor}
 * 中的桶级并行处理逻辑，消除两处 processBucket 重复代码。
 *
 * <p>每桶处理流程:
 * <ol>
 *   <li>竞争(可选) — 由 {@link BucketProcessingOptions.BucketContentionStrategy} 决定</li>
 *   <li>路径解析 — 用分桶字段值替换占位符</li>
 *   <li>构建 PipelineOptions 并应用 {@link BucketProcessingOptions.PipelineCustomizer}</li>
 *   <li>调用 {@code DownloadSupport.executePipeline} 执行单文件下载管线</li>
 *   <li>聚合结果到 {@link BucketBatchResult}</li>
 * </ol>
 */
@Slf4j
@Component
public class BucketProcessor {

    private final DownloadSupport downloadSupport;
    private final TransferSupport transferSupport;
    private final IntFunction<ExecutorService> batchExecutorFactory;

    public BucketProcessor(DownloadSupport downloadSupport,
                           TransferSupport transferSupport,
                           IntFunction<ExecutorService> batchExecutorFactory) {
        this.downloadSupport = downloadSupport;
        this.transferSupport = transferSupport;
        this.batchExecutorFactory = batchExecutorFactory;
    }

    /**
     * 并行处理一批桶。
     *
     * @param buckets      桶列表(为空时返回空结果)
     * @param config       传输配置
     * @param baseFileInfo 基础文件路径(用于占位符解析的模板, fullPath 作为 resolveFilePath 的 template)
     * @param ftpName      FTP 服务器名称
     * @param opts         处理选项(竞争策略 / 管线定制 / 并发度)
     * @param nodeId       当前节点 ID
     * @return 聚合结果(永不 null)
     */
    public BucketBatchResult processAll(List<Detail> buckets, TransferConfig config,
                                        ResolvedPath baseFileInfo, String ftpName,
                                        BucketProcessingOptions opts, String nodeId) {
        BucketBatchResult result = new BucketBatchResult();
        if (buckets == null || buckets.isEmpty()) {
            return result;
        }

        String splitFields = config.getSplitFields();
        int threadCount = Math.max(1, Math.min(buckets.size(), opts.getMaxConcurrency()));
        ExecutorService executor = batchExecutorFactory.apply(threadCount);
        try {
            for (Detail bucket : buckets) {
                executor.execute(() ->
                        processOne(bucket, config, baseFileInfo, ftpName, opts, nodeId, result, splitFields));
            }
        } finally {
            shutdownExecutor(executor);
        }
        return result;
    }

    /** 处理单个桶(运行在桶线程池中): 竞争 → 路径解析 → 管线 → 聚合 */
    private void processOne(Detail bucket, TransferConfig config,
                            ResolvedPath baseFileInfo, String ftpName,
                            BucketProcessingOptions opts, String nodeId,
                            BucketBatchResult result, String splitFields) {
        try {
            // 1. 竞争(可选)
            if (opts.getContentionStrategy() != null
                    && !opts.getContentionStrategy().tryAcquire(bucket, nodeId)) {
                return;
            }

            // 2. 路径解析
            Map<String, String> context = transferSupport.buildContext(
                    null, splitFields, bucket.getFieldValue());
            ResolvedPath targetFileInfo = transferSupport.resolveFilePath(
                    baseFileInfo.fullPath(), context);

            // 3. 构建管线选项(默认: 分桶模式, 前操作+后稽核开启, 仅 SUB 后操作)
            DownloadSupport.PipelineOptions pipelineOpts = DownloadSupport.PipelineOptions.builder()
                    .wholeTable(false)
                    .fieldValue(bucket.getFieldValue())
                    .expectedAuditCount(null)
                    .enablePreCheck(true)
                    .enableOverwriteCheck(false)
                    .enablePostAudit(true)
                    .postOpsFilter("SUB")
                    .detail(bucket)
                    .nodeId(nodeId)
                    .build();

            if (opts.getPipelineCustomizer() != null) {
                opts.getPipelineCustomizer().customize(pipelineOpts, bucket, targetFileInfo);
            }

            // 4. 执行管线
            DownloadSupport.PipelineResult pr = downloadSupport.executePipeline(
                    ftpName, config, targetFileInfo, pipelineOpts);

            // 5. 聚合结果
            result.accumulate(pr, targetFileInfo != null ? targetFileInfo.fullPath() : null);

        } catch (Exception e) {
            log.error("Bucket {} processing failed unexpectedly for table {}: {}", bucket.getId(), config.getTableName(), e.getMessage(), e);
            result.accumulateFailure();
        }
    }

    private void shutdownExecutor(ExecutorService executor) {
        TransferUtils.shutdownExecutor(executor, 1, TimeUnit.HOURS, "BucketProcessor");
    }

    // ==================== 值类型 ====================

    /**
     * 桶处理选项 — 控制竞争策略、管线定制和并发度。
     */
    @Value
    @Builder
    public static class BucketProcessingOptions {

        /** 桶竞争策略, null = 不竞争(单节点模式) */
        BucketContentionStrategy contentionStrategy;

        /** 每桶管线定制回调, null = 使用默认值(全开, 仅 SUB) */
        PipelineCustomizer pipelineCustomizer;

        /** 最大并发线程数, ≤0 或 >buckets.size() 时以桶数为准 */
        @Builder.Default
        int maxConcurrency = Integer.MAX_VALUE;

        @FunctionalInterface
        public interface BucketContentionStrategy {
            /** @return true=竞争成功(继续处理), false=竞争失败(跳过) */
            boolean tryAcquire(Detail bucket, String nodeId);
        }

        @FunctionalInterface
        public interface PipelineCustomizer {
            /**
             * 在默认 PipelineOptions 基础上做定制。
             * 默认值: wholeTable=false, enablePreCheck=true, enablePostAudit=true, postOpsFilter="SUB"
             */
            void customize(DownloadSupport.PipelineOptions options,
                           Detail bucket, ResolvedPath targetFileInfo);
        }
    }

    /**
     * 桶处理聚合结果 — 线程安全。
     *
     * <p>{@link #processAll} 返回后读取各计数即可(此时所有桶已处理完毕)。
     */
    public static class BucketBatchResult {
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

        // ========== getters ==========
        public int getTotalRecordCount() { return totalRecordCount.get(); }
        public int getSuccessCount() { return successCount.get(); }
        public int getFailedCount() { return failedCount.get(); }
        public int getSkippedCount() { return skippedCount.get(); }
        public boolean isAllSuccess() { return allSuccess.get(); }
        public List<String> getGeneratedFiles() {
            List<String> list = List.copyOf(generatedFiles);
            return list;
        }
    }
}
