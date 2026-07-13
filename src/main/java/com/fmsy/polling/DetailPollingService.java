package com.fmsy.polling;

import com.fmsy.config.AppConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.lifecycle.ConfigLoaderService;
import com.fmsy.lifecycle.ShutdownService;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.TempTransferConfigFactory;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.transfer.download.BucketProcessor;
import com.fmsy.transfer.download.BucketProcessor.BucketBatchResult;
import com.fmsy.transfer.download.BucketProcessor.BucketProcessingOptions;
import com.fmsy.transfer.download.DownloadSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.LogUtils;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 明细轮询服务 - S型子命令处理
 *
 * 功能说明：
 * - 被主线程调用，处理DOWNLOAD_MULTI_NODE场景下的S型子命令
 * - 从明细表获取待处理的桶，竞争处理权后委托 {@link BucketProcessor} 并行处理
 * - 各节点通过竞争机制分配桶，避免重复处理
 *
 * 线程模型：
 * - 每次 pollAndProcess 调用创建一个新的 ExecutorService
 * - 每个桶独立 Runnable 提交到该池,主线程 awaitTermination 等所有桶完成
 * - 与 PollingService 批次隔离原则一致:同一子命令的所有桶共享本池,与之前/之后子命令的桶不共用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetailPollingService {

    private final DetailRepository detailRepository;
    private final BucketDistributor bucketDistributor;
    private final ConfigLoaderService configLoader;
    private final CommandRepository commandRepository;
    private final TempTransferConfigFactory tempConfigFactory;
    private final AppConfig appConfig;
    private final TransferSupport transferSupport;
    private final ResultRepository resultRepository;
    private final BucketProcessor bucketProcessor;
    private final ShutdownService shutdownService;

    /**
     * 轮询并处理S型子命令分配的桶(并行版本 + 外层竞争循环)
     */
    public void pollAndProcess(String nodeId, String mainCommandId, Command subCommand) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(Long.parseLong(mainCommandId));
        // P1#6修复:S 型子命令也是 Command,补 setStartTime 让结果表写真正的处理起始时间
        if (subCommand != null) {
            subCommand.markStartTimeIfAbsent();
        }
        LogUtils.setNodeId(nodeId);
        int batchSize = appConfig.getDownload().getBucketBatchSize();
        int maxIterations = appConfig.getDownload().getMaxPollIterations();
        long timeoutMs = appConfig.getPolling().getTaskTimeoutHours() * 3600_000L;
        long mainCmdIdLong = Long.parseLong(mainCommandId);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        int totalBucketCount = 0;
        TransferConfig sharedConfig = null;

        try {
            // 提前解析配置，供所有桶共享（支持 T 类型主命令回溯）
            sharedConfig = resolveTempConfig(subCommand);
            if (sharedConfig == null) {
                log.error("Cannot resolve config for sub-command {}, marking ERROR", subCommand.getId());
                writeSubCommandResult(subCommand, startTime, null, 0, 0, 1, 0);
                return;
            }

            // 基础文件路径模板(含占位符),供 BucketProcessor 每桶解析
            ResolvedPath baseFileInfo = ResolvedPath.of(sharedConfig.getFilePath());

            int iteration = 0;
            while (iteration < maxIterations) {
                // 关闭检查:不开始新批次
                if (shutdownService.isShuttingDown()) {
                    log.info("Shutdown in progress, stop sub-command {} loop at iteration {}",
                            subCommand.getId(), iteration);
                    break;
                }
                // 超时检查
                if (System.currentTimeMillis() - startTime >= timeoutMs) {
                    log.error("Sub-command {} reached task timeout ({}ms) at iteration {}",
                            subCommand.getId(), timeoutMs, iteration);
                    break;
                }
                iteration++;

                List<Detail> buckets = detailRepository.findReadyBuckets(mainCmdIdLong, batchSize);
                if (buckets.isEmpty()) {
                    log.info("No more ready buckets for sub-command: {} (iteration {}, total={})",
                            subCommand.getId(), iteration, totalBucketCount);
                    break;
                }
                log.info("Sub-command {} iteration {}: fetched {} buckets (running total: {})",
                        subCommand.getId(), iteration, buckets.size(),
                        totalBucketCount + buckets.size());
                totalBucketCount += buckets.size();

                // 委托 BucketProcessor 并行处理本批次桶
                BucketBatchResult br = bucketProcessor.processAll(buckets, sharedConfig, baseFileInfo,
                        sharedConfig.getFtpName(),
                        BucketProcessingOptions.builder()
                                .contentionStrategy(
                                        (detail, nid) -> bucketDistributor.competeBucket(detail.getId(), nid) == 1)
                                .pipelineCustomizer((pipelineOpts, bucket, fileInfo) -> {
                                    pipelineOpts.setExpectedAuditCount(bucket.getAuditCount());
                                })
                                .build(),
                        nodeId);

                successCount.addAndGet(br.getSuccessCount());
                failedCount.addAndGet(br.getFailedCount());
                skippedCount.addAndGet(br.getSkippedCount());
            }
            if (iteration >= maxIterations) {
                log.error("Sub-command {} reached max iterations ({}); check bucket distributor or query logic",
                        subCommand.getId(), maxIterations);
            }
        } catch (RuntimeException e) {
            log.error("Bucket dispatch failed for sub-command: {}", subCommand.getId(), e);
            failedCount.incrementAndGet();
        } finally {
            // 写子命令结束的结果表记录(需求:每个指令结束都得写结果表)
            writeSubCommandResult(subCommand, startTime, sharedConfig, totalBucketCount,
                    successCount.get(), failedCount.get(), skippedCount.get());
        }
    }

    /** 写子命令结束的结果表记录(无论成功/异常都调用) */
    private void writeSubCommandResult(Command subCommand, long startTime, TransferConfig config, int bucketCount,
                                       int success, int failed, int skipped) {
        long durationMs = System.currentTimeMillis() - startTime;
        String status = determineSubCommandResult(failed, skipped, success);

        Result result = Result.builder()
                .commandId(subCommand.getId())
                .categoryCode(subCommand.getCategoryCode())
                .controlCode(subCommand.getControlCode())
                .ftpName(config != null ? config.getFtpName() : null)
                .filePath(config != null ? config.getFilePath() : null)
                .dbInfo(config != null ? config.getTableName() : null)
                .dbName(config != null ? config.getDbName() : ColumnNames.DEFAULT_DB)
                .transmissionDate(LocalDate.now())
                .status(status)
                .startTime(subCommand.getStartTime() != null ? subCommand.getStartTime() : LocalDateTime.now())
                .durationMs(durationMs)
                .recordCount(success)
                .fileSize(0L)
                .description("buckets: total=" + bucketCount + " success=" + success
                        + " failed=" + failed + " skipped=" + skipped)
                .transferDirection(Result.DIRECTION_DOWNLOAD)
                .build();
        resultRepository.insert(result);

        log.info("Sub-command {} finished: {} (buckets success={} failed={} skipped={}, {}ms)",
                subCommand.getId(), status, success, failed, skipped, durationMs);
    }

    /**
     * 解析 S 子命令对应的 TransferConfig。
     * 优先查传输配置表，若查不到且主命令是 T 类型，则从主命令的 temp_config 构建。
     *
     * @param subCommand S 型子命令
     * @return TransferConfig，完全查不到时返回 null
     */
    private TransferConfig resolveTempConfig(Command subCommand) {
        String cat = subCommand.getCategoryCode();
        String ctrl = subCommand.getControlCode();
        TransferConfig config = configLoader.getConfigOrDefault(cat, ctrl);
        if (config != null) {
            return config;
        }
        // 从 extraInfo 解析主命令 ID (格式: "mainId|baseFilePath")
        String extraInfo = subCommand.getExtraInfo();
        if (extraInfo == null || !extraInfo.contains("|")) {
            log.warn("Cannot resolve main command id from extraInfo: {}", extraInfo);
            return null;
        }
        String mainIdStr = extraInfo.substring(0, extraInfo.indexOf('|'));
        try {
            Command mainCommand = commandRepository.findById(Long.parseLong(mainIdStr));
            if (mainCommand != null && mainCommand.getCommandType() == CommandType.TEMPORARY) {
                log.info("Resolved config from T-type main command: {}", mainCommand.getId());
                return tempConfigFactory.build(mainCommand);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve temp config from main command: {}", e.getMessage());
        }
        return null;
    }

    private String determineSubCommandResult(int failed, int skipped, int success) {
        if (success == 0 && failed == 0 && skipped == 0) return ColumnNames.STATUS_SKIPPED;
        if (failed > 0 && success == 0 && skipped == 0) return ColumnNames.STATUS_ERROR;
        if (success == 0 && skipped > 0 && failed == 0) return ColumnNames.STATUS_SKIPPED;
        if (failed > 0) return ColumnNames.STATUS_ERROR;   // 任一桶失败 → 主子命令 E
        return ColumnNames.STATUS_SUCCESS;
    }
}
