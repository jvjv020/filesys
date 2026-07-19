package com.fmsy.transfer.download;

import com.fmsy.config.AppConfig;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.db.PartitionHelper;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.FilePathUtils;
import com.fmsy.util.LogUtils;
import com.fmsy.util.ResolvedPath;
import com.fmsy.util.SystemConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.io.OutputStream;

/**
 * 子节点桶处理器 — 处理 DOWNLOAD_MULTI_NODE 场景下 S 型子命令的桶执行。
 *
 * <p>子节点接到 S 子指令后,循环竞争空闲分桶:
 * <ol>
 *   <li>批量原子 UPDATE 竞争空闲桶(状态=空 → P,一次抢多个减少轮询)</li>
 *   <li>线程池并发处理多个桶</li>
 *   <li>解析 specName {@code "partitionName|pkStart|pkEnd"} 获取 PK 范围</li>
 *   <li>查目标表,用 writeDataRecords 仅写数据记录到临时文件</li>
 *   <li>上传临时文件到 FTP {@code {target_dir}/temp/{detailId}.tmp}</li>
 *   <li>更新明细状态为 Y(成功) 或 E(失败)</li>
 *   <li>退出:无空闲桶 + 主指令已拆分完成 → 退出</li>
 * </ol>
 *
 * <p>不写文件头/文件尾,由主节点合流程统一处理。
 * v2.0 实现,替代原 SChildCommandProcessor(v1.0)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChildBucketProcessor {

    private final DetailRepository detailRepository;
    private final CommandRepository commandRepository;
    private final TargetTableRepository targetTableRepository;
    private final PartitionHelper partitionHelper;
    private final ConverterFactory converterFactory;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final FtpPool ftpPool;
    private final TransferSupport transferSupport;
    private final ResultRepository resultRepository;
    private final AppConfig appConfig;
    private final IntFunction<ExecutorService> batchExecutorFactory;

    /**
     * 子节点入口 — 循环竞争桶并处理,直到所有桶处理完毕。
     * 使用线程池并发处理,批量竞争减少轮询次数。
     */
    public void pollAndProcess(String nodeId, Command subCommand, TransferConfig config) {
        long startTime = System.currentTimeMillis();
        Long subCmdId = subCommand.getId();
        LogUtils.setTaskId(subCmdId);
        LogUtils.setNodeId(nodeId);
        subCommand.markStartTimeIfAbsent();

        // 从 extraInfo 提取主命令 ID (格式: "mainId|baseFilePath")
        String extraInfo = subCommand.getExtraInfo();
        Long mainCommandId = extraInfo != null && extraInfo.contains("|")
                ? Long.parseLong(extraInfo.substring(0, extraInfo.indexOf('|')))
                : null;
        if (mainCommandId == null) {
            log.error("Cannot parse main command ID from extraInfo: {}", extraInfo);
            writeSubCommandResult(subCommand, startTime, config, 0, 0, 1, 0);
            return;
        }

        // 预构建 FieldMapping(所有桶复用)
        FieldMapping mapping = fieldMappingBuilder.buildForDownload(config);
        FileConverter converter = converterFactory.get(config.getParserType());
        List<String> pkColumns = partitionHelper.getPrimaryKeyColumns(
                config.getDbName(), config.getTableName());

        int maxIterations = appConfig.getDownload().getMaxPollIterations();
        long timeoutMs = appConfig.getPolling().getTaskTimeoutHours() * 3600_000L;

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        AtomicInteger totalBuckets = new AtomicInteger(0);

        String ftpName = config.getFtpName();
        ResolvedPath baseFileInfo = ResolvedPath.of(config.getFilePath());
        String targetParent = FilePathUtils.extractParentDirectory(baseFileInfo.fullPath());
        String tempDir = (targetParent != null ? targetParent : "") + "/" + SystemConstants.TEMP_DIR_NAME;

        // 并发度:配置的并行线程数,上限 5
        int concurrency = Math.min(appConfig.getDownload().getParallelThreads(), 5);
        ExecutorService executor = batchExecutorFactory.apply(concurrency);

        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                // 超时检查
                if (System.currentTimeMillis() - startTime >= timeoutMs) {
                    log.warn("Child[{}] timeout ({}ms) at iteration {}", subCmdId, timeoutMs, iteration);
                    break;
                }

                // 批量竞争桶(一次抢 concurrency 个)
                List<Detail> buckets = competeBuckets(mainCommandId, nodeId, concurrency);
                if (buckets.isEmpty()) {
                    boolean splitDone = commandRepository.isSplitDone(mainCommandId);
                    boolean hasEmpty = detailRepository.countByStatus(
                            mainCommandId, ColumnNames.STATUS_EMPTY) > 0;
                    if (splitDone && !hasEmpty) {
                        log.info("Child[{}] no more buckets, split done, exiting", subCmdId);
                        break;
                    }
                    // 指数退避:第1次2s,第2次4s,最大10s
                    TransferSupport.safeSleep(computeBackoffMs(iteration));
                    continue;
                }

                totalBuckets.addAndGet(buckets.size());

                // 提交到线程池并发处理
                for (Detail bucket : buckets) {
                    executor.execute(() -> {
                        try {
                            processBucket(bucket, config, ftpName, tempDir, pkColumns,
                                    converter, mapping, nodeId);
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            log.error("Child[{}] bucket {} failed: {}", subCmdId,
                                    bucket.getId(), e.getMessage(), e);
                            detailRepository.updateStatus(bucket.getId(),
                                    ColumnNames.STATUS_ERROR, nodeId);
                            // 桶失败时尽快通知主命令,避免子命令结果写入后、通知前进程崩溃
                            // 导致主命令卡在 P 状态(后续 writeSubCommandResult 中也会再调用一次,
                            // 但 updateStatusIfProcessing 保证幂等)
                            notifyMainCommandOnFailure(subCommand, config);
                            failedCount.incrementAndGet();
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.error("Child[{}] unexpected: {}", subCmdId, e.getMessage(), e);
            failedCount.incrementAndGet();
        } finally {
            // 等待所有已提交的桶处理完毕
            TransferSupport.shutdownExecutor(executor, 1, TimeUnit.HOURS, "ChildBucketProcessor");
        }

        writeSubCommandResult(subCommand, startTime, config,
                totalBuckets.get(), successCount.get(), failedCount.get(), 0);
    }

    /**
     * 批量竞争空闲桶—一次抢多个,减少轮询次数。
     * 原子 UPDATE 竞争,已被其他节点抢走的自动跳过。
     */
    private List<Detail> competeBuckets(Long mainCommandId, String nodeId, int limit) {
        List<Detail> candidates = detailRepository.findReadyBuckets(mainCommandId, limit);
        if (candidates.isEmpty()) return List.of();

        List<Detail> won = new ArrayList<>();
        for (Detail bucket : candidates) {
            int affected = detailRepository.competeBucket(bucket.getId(), nodeId);
            if (affected == 1) {
                won.add(bucket);
            }
        }
        return won;
    }

    /** 计算退避时间:第1次2s,第2次4s,最大10s */
    private static long computeBackoffMs(int iteration) {
        if (iteration <= 0) return 2000;
        return Math.min(10000L, 2000L * (1L << Math.min(iteration, 3)));
    }

    /** 处理单个桶:查数据 → 写数据记录到临时文件 → 上传 FTP */
    private void processBucket(Detail bucket, TransferConfig config, String ftpName,
                                String tempDir, List<String> pkColumns,
                                FileConverter converter, FieldMapping mapping,
                                String nodeId) throws Exception {
        String tempFilePath = tempDir + "/" + bucket.getId() + SystemConstants.TEMP_FILE_SUFFIX;
        ftpPool.withClient(ftpName, client -> {
            client.mkdirs(tempDir);
            return null;
        });

        String specName = bucket.getSpecName();
        if (specName != null && !specName.isEmpty()) {
            // Path A: PK 范围查询(由 SplitFlowService 创建,含 specName)
            processByPkRange(bucket, config, ftpName, tempFilePath, pkColumns, converter, mapping,
                    specName);
        } else {
            // Path B: 字段值降级查询(BATCH 模式或外部预填的 field_value 桶)
            String fieldValue = bucket.getFieldValue();
            if (fieldValue == null || fieldValue.isEmpty()) {
                throw new IllegalArgumentException("Bucket " + bucket.getId()
                        + " has neither specName nor fieldValue");
            }
            processByFieldValue(config, ftpName, tempFilePath, converter, mapping, fieldValue);
        }

        // 5) 更新明细为 Y
        detailRepository.updateStatus(bucket.getId(), ColumnNames.STATUS_SUCCESS, nodeId);
    }

    // ==================== 桶查询策略 ====================

    /**
     * Path A: 按 PK 范围查数据并写临时文件。
     * specName 格式: "partitionName|pkStart|pkEnd"
     */
    private void processByPkRange(Detail bucket, TransferConfig config, String ftpName,
                                   String tempFilePath, List<String> pkColumns,
                                   FileConverter converter, FieldMapping mapping,
                                   String specName) throws Exception {
        String[] parts = specName.split("\\|", 3);
        String actualTable = parts[0];
        String pkStartRaw = parts.length > 1 ? parts[1] : "";
        String pkEndRaw = parts.length > 2 ? parts[2] : "LAST";
        boolean hasEndBound = !"LAST".equals(pkEndRaw);

        // specName 为 ""|start|end 时(非分区表),actualTable="" 需用原表名
        String queryTable = !actualTable.isEmpty() ? actualTable : config.getTableName();

        List<Object> pkStart = parsePkValues(pkStartRaw);
        List<Object> pkEnd = hasEndBound ? parsePkValues(pkEndRaw) : null;

        try (var data = targetTableRepository.streamByPkRange(
                config.getDbName(), queryTable, pkColumns,
                pkStart.isEmpty() ? null : pkStart,
                pkEnd)) {
            ftpPool.withClient(ftpName, client -> {
                try (OutputStream os = client.getOutputStream(tempFilePath)) {
                    converter.writeDataRecords(os, data, mapping);
                }
                client.completePendingCommand();
                return null;
            });
        }
    }

    /**
     * Path B: 按字段值查数据并写临时文件(降级路径)。
     * 适用于 BATCH 模式或外部预填的 field_value 桶(无 specName 但有 fieldValue)。
     */
    private void processByFieldValue(TransferConfig config, String ftpName,
                                     String tempFilePath, FileConverter converter,
                                     FieldMapping mapping, String fieldValue) throws Exception {
        String splitFields = config.getSplitFields();
        if (splitFields == null || splitFields.isEmpty()) {
            throw new IllegalArgumentException("No splitFields in config for fieldValue bucket");
        }
        try (var data = targetTableRepository.streamBucketData(
                config.getDbName(), config.getTableName(),
                splitFields, fieldValue)) {
            ftpPool.withClient(ftpName, client -> {
                try (OutputStream os = client.getOutputStream(tempFilePath)) {
                    converter.writeDataRecords(os, data, mapping);
                }
                client.completePendingCommand();
                return null;
            });
        }
    }

    /** 将 PK 值的逗号串(如 "100,200")解析为 Object 列表 */
    private static List<Object> parsePkValues(String raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        String[] vals = raw.split(",", -1);
        List<Object> result = new ArrayList<>(vals.length);
        for (String v : vals) {
            String t = v.trim();
            if (t.isEmpty()) { result.add(null); continue; }
            try {
                result.add(t.contains(".") ? (Object) Double.parseDouble(t) : Long.parseLong(t));
            } catch (NumberFormatException e) {
                result.add(t); // 字符串 PK(UUID 等)
            }
        }
        return result;
    }

    // ==================== 子命令结果 ====================

    /** 写子命令结束的结果表记录,子命令失败时同步通知主命令 */
    private void writeSubCommandResult(Command subCommand, long startTime,
                                        TransferConfig config, int totalBuckets,
                                        int success, int failed, int skipped) {
        long durationMs = System.currentTimeMillis() - startTime;
        String status = (failed > 0) ? ColumnNames.STATUS_ERROR
                : (success > 0) ? ColumnNames.STATUS_SUCCESS : ColumnNames.STATUS_SKIPPED;

        Result r = Result.builder()
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
                .description("buckets: total=" + totalBuckets + " success=" + success
                        + " failed=" + failed + " skipped=" + skipped)
                .transferDirection(Result.DIRECTION_DOWNLOAD)
                .build();
        resultRepository.insert(r);

        // 子命令失败时直接通知主命令,避免 MergeFlowService 成为唯一错误传播路径
        if (failed > 0) {
            notifyMainCommandOnFailure(subCommand, config);
        }

        log.info("Child[{}] finished: {} (buckets total={} success={} failed={}, {}ms)",
                subCommand.getId(), status, totalBuckets, success, failed, durationMs);
    }

    /**
     * 子命令失败时通知主命令:将主命令置 ERROR + 写结果行。
     * best-effort:主命令可能已被其他子命令或 MergeFlowService 更新过状态。
     */
    private void notifyMainCommandOnFailure(Command subCommand, TransferConfig config) {
        String extraInfo = subCommand.getExtraInfo();
        if (extraInfo == null || !extraInfo.contains("|")) {
            log.warn("Cannot parse main command ID from extraInfo: {}", extraInfo);
            return;
        }
        try {
            Long mainCommandId = Long.parseLong(extraInfo.substring(0, extraInfo.indexOf('|')));
            // 原子条件更新:仅当主命令仍为 P 时才置 E
            int affected = commandRepository.updateStatusIfProcessing(mainCommandId,
                    ColumnNames.STATUS_ERROR);
            if (affected > 0) {
                commandRepository.markErrorWithResult(mainCommandId,
                        subCommand.getCategoryCode(), subCommand.getControlCode(),
                        "Sub-command " + subCommand.getId() + " failed");
                log.warn("Notified main command {} of sub-command {} failure", mainCommandId,
                        subCommand.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to notify main command on sub-command failure: {}", e.getMessage());
        }
    }

    }