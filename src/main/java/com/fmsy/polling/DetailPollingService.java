package com.fmsy.polling;

import com.fmsy.audit.AuditScenario;
import com.fmsy.audit.AuditService;
import com.fmsy.config.AppConfig;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.CommandType;
import com.fmsy.model.FieldMapping;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.lifecycle.ShutdownService;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.TempTransferConfigFactory;
import com.fmsy.lifecycle.ConfigLoaderService;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.FilePathUtils;
import com.fmsy.util.LogUtils;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * 明细轮询服务 - S型子命令处理
 *
 * 功能说明：
 * - 被主线程调用，处理DOWNLOAD_MULTI_NODE场景下的S型子命令
 * - 从明细表获取待处理的桶，竞争处理权后并行处理(每子命令独立线程池)
 * - 各节点通过竞争机制分配桶，避免重复处理
 *
 * 处理流程（针对每个桶）：
 * 1. 竞争桶的处理权
 * 2. 前置检查（READY/FLAG文件）
 * 3. 预审计（按明细表的auditCount验证）
 * 4. 空数据处理
 * 5. 查询该桶数据，生成文件到FTP
 * 6. 后置处理（GENERATE_SUB_FLAG等）
 * 7. 后审计
 * 8. 更新明细状态
 *
 * 线程模型：
 * - 每次 pollAndProcess 调用创建一个新的 ExecutorService
 * - 每个桶独立 Runnable 提交到该池,主线程 awaitTermination 等所有桶完成
 * - 与 PollingService 批次隔离原则一致:同一子命令的所有桶共享本池,与之前/之后子命令的桶不共用
 *
 * <p>Phase 1 重构:SQL 访问委托给 DetailRepository / TargetTableRepository。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetailPollingService {

    private final DetailRepository detailRepository;
    private final TargetTableRepository targetTableRepository;
    private final BucketDistributor bucketDistributor;
    private final ConfigLoaderService configLoader;
    private final CommandRepository commandRepository;
    private final TempTransferConfigFactory tempConfigFactory;
    private final AppConfig appConfig;
    private final FtpPool ftpPool;
    private final TransferSupport transferSupport;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final FlagFileService flagFileService;
    private final AuditService auditService;
    private final ResultRepository resultRepository;
    /** 每子命令独立的批处理线程池工厂 */
    private final IntFunction<ExecutorService> batchExecutorFactory;
    /** 关闭服务(轮询期间检查是否正在关闭) */
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
        ExecutorService bucketExecutor = null;

        try {
            // 提前解析配置，供所有桶共享（支持 T 类型主命令回溯）
            TransferConfig sharedConfig = resolveTempConfig(subCommand);
            if (sharedConfig == null) {
                log.error("Cannot resolve config for sub-command {}, marking ERROR", subCommand.getId());
                writeSubCommandResult(subCommand, startTime, 0, 0, 1, 0);
                return;
            }

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

                // 本批次独立线程池,与之前/之后批次隔离
                bucketExecutor = batchExecutorFactory.apply(buckets.size());
                for (Detail bucket : buckets) {
                    TransferConfig bucketConfig = sharedConfig;
                    bucketExecutor.execute(() -> runOneBucket(bucket, nodeId, bucketConfig, successCount, failedCount, skippedCount));
                }
                bucketExecutor.shutdown();
                if (!bucketExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.HOURS)) {
                    log.error("Bucket processing timed out for sub-command: {} (iteration {})",
                            subCommand.getId(), iteration);
                }
                // 本批次完成,清空引用供 finally 区分(下轮会重新赋值)
                bucketExecutor = null;
            }
            if (iteration >= maxIterations) {
                log.error("Sub-command {} reached max iterations ({}); check bucket distributor or query logic",
                        subCommand.getId(), maxIterations);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Bucket processing interrupted for sub-command: {}", subCommand.getId());
        } catch (RuntimeException e) {
            // 派发或 await 期间的未受检异常(RejectedExecutionException 等):
            // 计为失败,保证后续结果表写入正确反映状态
            log.error("Bucket dispatch failed for sub-command: {}", subCommand.getId(), e);
            failedCount.incrementAndGet();
        } finally {
            // 资源回收:池未终止时强制 shutdownNow,防止线程泄漏
            if (bucketExecutor != null && !bucketExecutor.isTerminated()) {
                bucketExecutor.shutdownNow();
            }
            // 写子命令结束的结果表记录(需求:每个指令结束都得写结果表)
            // 放在 finally 内确保异常路径也会执行
            writeSubCommandResult(subCommand, startTime, totalBucketCount,
                    successCount.get(), failedCount.get(), skippedCount.get());
        }
    }

    /** 写子命令结束的结果表记录(无论成功/异常都调用) */
    private void writeSubCommandResult(Command subCommand, long startTime, int bucketCount,
                                       int success, int failed, int skipped) {
        long durationMs = System.currentTimeMillis() - startTime;
        String status = determineSubCommandResult(failed, skipped, success);
        // 配置在子命令执行期间被卸载时也能写一条带默认 DB 的结果表
        TransferConfig config = resolveTempConfig(subCommand);

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

    /** 单桶执行入口(运行在桶线程池中):try/catch + 计数累加,异常不逃逸 */
    private void runOneBucket(Detail bucket, String nodeId, TransferConfig config,
                              AtomicInteger successCount, AtomicInteger failedCount, AtomicInteger skippedCount) {
        try {
            int affected = bucketDistributor.competeBucket(bucket.getId(), nodeId);
            if (affected != 1) {
                log.debug("Bucket {} not acquired (affected={})", bucket.getId(), affected);
                return;
            }
            log.info("Competed bucket: {}, fieldValue: {}", bucket.getId(), bucket.getFieldValue());
            BucketOutcome outcome = processBucket(bucket, nodeId, config);
            switch (outcome) {
                case SUCCESS -> successCount.incrementAndGet();
                case FAILED -> failedCount.incrementAndGet();
                case SKIPPED -> skippedCount.incrementAndGet();
            }
        } catch (Throwable t) {
            log.error("Bucket processing crashed: {}", bucket.getId(), t);
            failedCount.incrementAndGet();
        }
    }

    private enum BucketOutcome { SUCCESS, FAILED, SKIPPED }

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

    /** 处理单个桶的数据,返回处理结果(供外层汇总) */
    private BucketOutcome processBucket(Detail bucket, String nodeId, TransferConfig config) {
        // config 由调用方传入(已通过 resolveTempConfig 解析),直接使用
        if (config == null) {
            log.warn("No config found for bucket: {}, category={}, control={}",
                    bucket.getId(), bucket.getCategoryCode(), bucket.getControlCode());
            detailRepository.updateStatus(bucket.getId(), ColumnNames.STATUS_ERROR, nodeId);
            return BucketOutcome.FAILED;
        }

        // Phase 1: DB-only checks (no FTP client held)
        String splitFields = config.getSplitFields();
        // 优先从 preAudit 获取桶记录数，避免二次 countByBucket
        int recordCount = -1;
        Integer detailAuditCount = bucket.getAuditCount();
        if (detailAuditCount != null && detailAuditCount >= 0) {
            recordCount = auditService.preAuditByBucket(
                    config.getTableName(), splitFields, bucket.getFieldValue(), detailAuditCount);
            if (recordCount < 0) {
                log.error("Pre-audit failed for bucket: {}", bucket.getId());
                detailRepository.updateStatus(bucket.getId(), ColumnNames.STATUS_SKIPPED, nodeId);
                return BucketOutcome.SKIPPED;
            }
        }
        if (recordCount < 0) {
            recordCount = targetTableRepository.countByBucket(
                    config.getDbName(), config.getTableName(), splitFields, bucket.getFieldValue());
        }

        EmptyDataHandling emptyHandling = config.getEmptyDataHandling();
        if (!transferSupport.handleEmptyData(recordCount, emptyHandling)) {
            log.warn("Empty data handling for bucket: {}", bucket.getFieldValue());
            if (emptyHandling == EmptyDataHandling.ERROR) {
                detailRepository.updateStatus(bucket.getId(), ColumnNames.STATUS_ERROR, nodeId);
                return BucketOutcome.FAILED;
            } else if (emptyHandling == EmptyDataHandling.SKIP) {
                detailRepository.updateStatus(bucket.getId(), ColumnNames.STATUS_SKIPPED, nodeId);
                return BucketOutcome.SKIPPED;
            }
        }

        Map<String, String> bucketContext = transferSupport.buildContext(
                null, splitFields, bucket.getFieldValue());
        ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), bucketContext);

        // Phase 2: FTP operations (borrow client just before use)
        FtpClient client = ftpPool.getClient(config.getFtpName());
        long startTime = System.currentTimeMillis();
        try {
            if (!transferSupport.preCheck(client, config, fileInfo)) {
                log.error("Pre-check failed for bucket: {}", bucket.getId());
                detailRepository.updateStatus(bucket.getId(), ColumnNames.STATUS_SKIPPED, nodeId);
                return BucketOutcome.SKIPPED;
            }

            String parentDir = FilePathUtils.extractParentDirectory(fileInfo.fullPath());
            if (parentDir != null && !parentDir.isEmpty()) {
                client.mkdirs(parentDir);
            }

            FileConverter converter = ConverterFactory.get(config.getParserType());
            FieldMapping mapping = fieldMappingBuilder.buildForDownload(config);

            try (var data = targetTableRepository.streamBucketData(
                    config.getDbName(), config.getTableName(), splitFields, bucket.getFieldValue());
                 OutputStream os = client.getOutputStream(fileInfo.fullPath())) {
                converter.generate(os, data, mapping);
                client.completePendingCommand();
            }
            log.info("Generated file for bucket: {}, path: {}, records: {}",
                    bucket.getId(), fileInfo.fullPath(), recordCount);

            // 每桶 sub-flag：使用新关键字 SUB 和 ResolvedPath
            String subFlagOnly = FlagFileService.filterOpsByType(
                    config.getPostOperations(), "SUB");
            if (subFlagOnly != null) {
                Map<String, String> extra = new java.util.HashMap<>();
                extra.put("C", String.valueOf(recordCount));
                flagFileService.process(client, subFlagOnly, fileInfo, extra);
            }
        } catch (Exception e) {
            log.error("Failed to process bucket: {}", bucket.getId(), e);
            detailRepository.updateStatus(bucket.getId(), ColumnNames.STATUS_ERROR, nodeId);
            return BucketOutcome.FAILED;
        } finally {
            client.close();
        }

        // Phase 3: post-audit (borrows its own FTP client internally)
        auditService.postAudit(AuditScenario.DOWNLOAD, config.getFtpName(), config.getTableName(), fileInfo.fullPath());

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("Bucket processed successfully: detailId={}, fieldValue={}, filePath={}, records={}, durationMs={}, nodeId={}",
                bucket.getId(), bucket.getFieldValue(), fileInfo.fullPath(), recordCount, durationMs, nodeId);

        detailRepository.updateStatus(bucket.getId(), ColumnNames.STATUS_SUCCESS, nodeId);
        return BucketOutcome.SUCCESS;
    }

}
