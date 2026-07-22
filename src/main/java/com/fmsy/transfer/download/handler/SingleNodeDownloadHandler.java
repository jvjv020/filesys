package com.fmsy.transfer.download.handler;

import com.fmsy.enums.CommandType;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.transfer.download.BucketBatchResult;
import com.fmsy.transfer.download.DownloadSupport;
import com.fmsy.transfer.download.multi.BucketDistributor;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static com.fmsy.transfer.TransferSupport.determineMainStatus;

/**
 * DOWNLOAD_SINGLE_NODE 场景：按拆分字段分桶，每个桶生成一个文件，并行下载到同一节点 FTP。
 *
 * <p>
 * 阶段顺序：<br>
 * preCheck → pre-audit（SERIAL）→ 分桶 → 并行桶处理 → 总标志文件 → 聚合后稽核
 *
 * <p>
 * <b>并行桶处理</b>：通过线程池提交所有桶，每桶走 {@link DownloadSupport#executeBucketPipeline} 管线，
 * 结果聚合到 {@link BucketBatchResult}。前操作/覆盖检查/后稽核由顶层统一完成，桶级别关闭。
 *
 * <p>
 * <b>总标志文件</b>：仅在所有桶成功时生成（{@code postOperations} 包含 TOTAL 类型时）。<br>
 * <b>聚合后稽核</b>：对所有桶整体做后稽核，失败时回滚删除所有已生成的 FTP 文件。
 *
 * <p>
 * <b>BATCH 模式</b>：从明细表加载桶，每个桶的 auditCount 由明细记录指定。<br>
 * <b>SERIAL 模式</b>：从 distinctBuckets 获取桶列表，顶层 pre-audit 使用命令级 auditCount。
 *
 * <p>
 * FieldMapping 在所有桶间共享（预构建一次，避免重复查表元数据），
 * FTP 连接在各桶管线内部独立管理（与上传 Handler 风格一致）。
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

        /* ---------- Phase 1: splitFields + 顶层 preCheck ---------- */
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

        /* ---------- Phase 2: SERIAL 模式顶层 pre-audit ---------- */
        if (commandType != CommandType.BATCH && command.getAuditCount() != null && command.getAuditCount() >= 0) {
            int auditResult = downloadSupport.preAudit(config, command.getAuditCount());
            if (auditResult < 0) {
                result.setOutcome(0, determineMainStatus(false, 0, 1), "Pre-audit failed");
                return;
            }
        }

        /* ---------- Phase 3: 分桶 ---------- */
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

        /* ---------- Phase 4: 并行桶处理 ---------- */
        FieldMapping sharedMapping = fieldMappingBuilder.buildForDownload(config);
        int concurrency = config.getConcurrency() != null ? config.getConcurrency() : 3;
        BucketBatchResult br = processBucketsInParallel(buckets, config, baseFileInfo,
                splitFields, sharedMapping, concurrency, commandType, config.getNodeId());

        /* ---------- Phase 5: 总标志文件(仅在所有桶成功时生成) ---------- */
        if (br.isAllSuccess() && postOps != null && postOps.contains("T")) {
            String totalFlagOps = FlagFileService.filterOpsByType(postOps, "T");
            if (totalFlagOps != null) {
                transferSupport.executeWithClient(config.getFtpName(), postClient -> {
                    transferSupport.postProcess(postClient, totalFlagOps, baseFileInfo, null);
                    return null;
                });
            }
        }

        /* ---------- Phase 6: 聚合后稽核 ---------- */
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
     * 并行处理所有桶 — 每桶走 {@link DownloadSupport#executeBucketPipeline} 管线，
     * 结果聚合到 {@link BucketBatchResult}。
     *
     * <p>桶的数量决定了线程池大小：{@code min(buckets.size(), concurrency)}，
     * 确保不会为大量空桶创建过多线程。所有桶处理完成后关闭线程池（最多等待 1 小时）。
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

    /**
     * 处理单个桶：路径解析 → executeBucketPipeline → 聚合结果。
     */
    private void processOneBucket(Detail bucket, TransferConfig config,
                                  ResolvedPath baseFileInfo, String splitFields,
                                  FieldMapping sharedMapping, CommandType commandType,
                                  String nodeId, BucketBatchResult result, String ftpName) {
        try {
            Map<String, String> context = transferSupport.buildContext(
                    null, splitFields, bucket.getFieldValue());
            ResolvedPath targetFileInfo = transferSupport.resolveFilePath(
                    baseFileInfo.fullPath(), context);

            Integer expectedAuditCount = null;
            if (commandType == CommandType.BATCH) {
                expectedAuditCount = bucket.getAuditCount();
            }

            DownloadSupport.PipelineResult pr = downloadSupport.executeBucketPipeline(
                    ftpName, config, targetFileInfo,
                    bucket.getFieldValue(), expectedAuditCount, sharedMapping,
                    false, false, "U", bucket, nodeId);

            result.accumulate(pr, targetFileInfo != null ? targetFileInfo.fullPath() : null);

        } catch (Exception e) {
            log.error("Bucket {} processing failed for table {}: {}", bucket.getId(), config.getTableName(), e.getMessage(), e);
            result.accumulateFailure();
        }
    }
}