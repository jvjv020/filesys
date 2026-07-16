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

import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 子节点桶处理器 — 处理 DOWNLOAD_MULTI_NODE 场景下 S 型子命令的桶执行。
 *
 * <p>子节点接到 S 子指令后,循环竞争空闲分桶:
 * <ol>
 *   <li>原子 UPDATE 竞争空闲桶(状态=空 → P)</li>
 *   <li>解析 specName {@code "partitionName|pkStart|pkEnd"} 获取 PK 范围</li>
 *   <li>查目标表,用 writeDataRecords 仅写数据记录到临时文件</li>
 *   <li>上传临时文件到 FTP {@code {target_dir}/temp/{detailId}.tmp}</li>
 *   <li>更新明细状态为 Y(成功) 或 E(失败)</li>
 *   <li>检查退出:无空闲桶 + 主指令已拆分完成 → 退出</li>
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

    /**
     * 子节点入口 — 循环竞争桶并处理,直到所有桶处理完毕。
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

        int successCount = 0;
        int failedCount = 0;
        int totalBuckets = 0;

        String ftpName = config.getFtpName();
        ResolvedPath baseFileInfo = ResolvedPath.of(config.getFilePath());
        String targetParent = FilePathUtils.extractParentDirectory(baseFileInfo.fullPath());
        String tempDir = (targetParent != null ? targetParent : "") + "/" + SystemConstants.TEMP_DIR_NAME;

        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                // 超时检查
                if (System.currentTimeMillis() - startTime >= timeoutMs) {
                    log.warn("Child[{}] timeout ({}ms) at iteration {}", subCmdId, timeoutMs, iteration);
                    break;
                }

                // 竞争一个空闲桶
                Detail bucket = competeBucket(mainCommandId, nodeId);
                if (bucket == null) {
                    boolean splitDone = commandRepository.isSplitDone(mainCommandId);
                    boolean hasEmpty = detailRepository.countByStatus(
                            mainCommandId, ColumnNames.STATUS_EMPTY) > 0;
                    if (splitDone && !hasEmpty) {
                        log.info("Child[{}] no more buckets, split done, exiting", subCmdId);
                        break;
                    }
                    safeSleep(2000);
                    continue;
                }

                totalBuckets++;

                try {
                    processBucket(bucket, config, ftpName, tempDir, pkColumns, converter, mapping, nodeId);
                    successCount++;
                } catch (Exception e) {
                    log.error("Child[{}] bucket {} failed: {}", subCmdId, bucket.getId(), e.getMessage(), e);
                    detailRepository.updateStatus(bucket.getId(), ColumnNames.STATUS_ERROR, nodeId);
                    failedCount++;
                }
            }
        } catch (Exception e) {
            log.error("Child[{}] unexpected: {}", subCmdId, e.getMessage(), e);
            failedCount++;
        }

        writeSubCommandResult(subCommand, startTime, config, totalBuckets, successCount, failedCount, 0);
    }

    /** 原子竞争一个空闲桶,竞争成功返回 Detail,失败返回 null */
    private Detail competeBucket(Long mainCommandId, String nodeId) {
        List<Detail> buckets = detailRepository.findReadyBuckets(mainCommandId, 1);
        if (buckets.isEmpty()) return null;
        Detail bucket = buckets.get(0);
        int affected = detailRepository.competeBucket(bucket.getId(), nodeId);
        return affected == 1 ? bucket : null;
    }

    /** 处理单个桶:按 PK 范围查数据 → 写数据记录到临时文件 → 上传 FTP */
    private void processBucket(Detail bucket, TransferConfig config, String ftpName,
                                String tempDir, List<String> pkColumns,
                                FileConverter converter, FieldMapping mapping,
                                String nodeId) throws Exception {
        // 1) 解析 specName = "partitionName|pkStart|pkEnd"
        String specName = bucket.getSpecName();
        if (specName == null || specName.isEmpty()) {
            throw new IllegalArgumentException("Bucket " + bucket.getId() + " has no specName");
        }
        String[] parts = specName.split("\\|", 3);
        String actualTable = parts[0];
        String pkStartRaw = parts.length > 1 ? parts[1] : "";
        String pkEndRaw = parts.length > 2 ? parts[2] : "LAST";
        boolean hasEndBound = !"LAST".equals(pkEndRaw);

        // specName 为 ""|start|end 时(非分区表),actualTable="" 需用原表名
        String queryTable = !actualTable.isEmpty() ? actualTable : config.getTableName();

        // 2) 构建 PK 范围参数
        List<Object> pkStart = parsePkValues(pkStartRaw);
        List<Object> pkEnd = hasEndBound ? parsePkValues(pkEndRaw) : null;

        // 3) 确保 temp 目录存在
        String tempFilePath = tempDir + "/" + bucket.getId() + SystemConstants.TEMP_FILE_SUFFIX;
        ftpPool.withClient(ftpName, client -> {
            client.mkdirs(tempDir);
            return null;
        });

        // 4) 查数据并写临时文件(仅数据记录,不带头尾)
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

        // 5) 创建 OK 哨兵文件,标记临时文件已完整写入
        //    主节点合流程见 .ok 才合并该 .tmp,防止读取不完整的文件
        String okFilePath = tempDir + "/" + bucket.getId() + SystemConstants.OK_FILE_SUFFIX;
        ftpPool.withClient(ftpName, client -> {
            try (OutputStream os = client.getOutputStream(okFilePath)) {
                // 写入空文件即表示完成信号
            }
            client.completePendingCommand();
            return null;
        });

        // 6) 更新明细为 Y
        detailRepository.updateStatus(bucket.getId(), ColumnNames.STATUS_SUCCESS, nodeId);
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

    /** 写子命令结束的结果表记录 */
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

        log.info("Child[{}] finished: {} (buckets total={} success={} failed={}, {}ms)",
                subCommand.getId(), status, totalBuckets, success, failed, durationMs);
    }

    private static void safeSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
