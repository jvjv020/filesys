package com.fmsy.transfer.download.multi;

import com.fmsy.config.AppConfig;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.db.PartitionHelper;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Detail;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.SplitFieldHelper;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.FilePathUtils;
import com.fmsy.util.LogUtils;
import com.fmsy.util.ResolvedPath;
import com.fmsy.util.SystemConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 多节点下载流程服务 — 将目标表按主键序切分为分桶（Plan B），
 * 子节点生成临时文件后合并到目标文件。
 *
 * <p>合并 {@link com.fmsy.transfer.download.SplitFlowService} 和
 * {@link com.fmsy.transfer.download.MergeFlowService} 而来，
 * 两者都是 {@link com.fmsy.transfer.download.handler.MultiNodeDownloadHandler} 触发的
 * 多节点协调流程的组成部分，共享大量依赖。
 *
 * <p>两种模式:
 * <ul>
 *   <li><b>单文件下发</b>:整表直接按主键序切分,所有桶合并到同一个目标文件</li>
 *   <li><b>拆分下发</b>:先查 DISTINCT 拆分字段值,再逐值按该条件检索主键边界切分,
 *       不同值的目标文件不同</li>
 * </ul>
 *
 * <p>分区表策略:逐分区执行 Plan B 切分。
 *
 * <p>合并流程:
 * <ul>
 *   <li>拆分下发:按 fieldValue 分组,不同文件并发合并,同文件串行 APPE</li>
 *   <li>单文件下发:所有临时文件串行 APPE 到同一个目标文件</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiNodeFlowService {

    private final PartitionHelper partitionHelper;
    private final TargetTableRepository targetTableRepository;
    private final DetailRepository detailRepository;
    private final CommandRepository commandRepository;
    private final AppConfig appConfig;
    private final FtpPool ftpPool;
    private final TransferSupport transferSupport;
    private final ConverterFactory converterFactory;
    private final FieldMappingBuilder fieldMappingBuilder;

    /** 每命令的终止标志 — 避免多命令间共享 volatile 字段造成误中断 */
    private final ConcurrentHashMap<Long, Boolean> abortFlags = new ConcurrentHashMap<>();

    // ==================== 拆分流程 ====================

    /**
     * 同步拆分执行 — 创建 PK 范围桶并写入明细表,完成后标记 splitDone。
     * <p>供 MultiNodeDownloadHandler 在 SERIAL 模式下同步调用,确保桶已创建后再创建 S 子命令。
     */
    public void splitSync(Long mainCommandId, TransferConfig config) {
        LogUtils.setTaskId(mainCommandId);
        doSplit(mainCommandId, config);
        log.info("Sync split completed for command: {}", mainCommandId);
    }

    /**
     * 异步拆分 + 完成回调。
     * 拆分完成后调用 onComplete(含 markSplitDone),异常时调用 onError。
     * 供 MultiNodeDownloadHandler 在 SERIAL 模式下异步切分,避免阻塞 Handler 线程。
     *
     * @param onComplete 拆分成功完成回调(负责创建子命令 + 启动合并)
     * @param onError    拆分失败回调(负责置主命令为 E)
     */
    public void startSplitAsync(Long mainCommandId, TransferConfig config,
                                Runnable onComplete, Consumer<Exception> onError) {
        CompletableFuture.runAsync(() -> {
            LogUtils.setTaskId(mainCommandId);
            try {
                doSplit(mainCommandId, config);
                commandRepository.markSplitDone(mainCommandId);
                log.info("Split flow completed for command: {}", mainCommandId);
                onComplete.run();
            } catch (Exception e) {
                log.error("Split flow failed for command {}: {}", mainCommandId, e.getMessage(), e);
                onError.accept(e);
            }
        });
    }

    /** 拆分主逻辑:判断是拆分下发还是单文件下发,分别处理 */
    private void doSplit(Long mainCommandId, TransferConfig config) {
        String dbName = config.getDbName();
        String tableName = config.getTableName();
        int bucketSize = appConfig.getDownload().getBucketRecordSize();
        List<String> pkColumns = partitionHelper.getPrimaryKeyColumns(dbName, tableName);

        if (pkColumns.isEmpty()) {
            throw new IllegalStateException("Table " + tableName + " has no primary key, cannot split");
        }

        String splitFields = config.getSplitFields();
        boolean isSplitDownload = splitFields != null && !splitFields.isEmpty();

        log.info("Split[{}]: table={}, pk={}, split={}, bucketSize={}",
                mainCommandId, tableName, pkColumns, isSplitDownload, bucketSize);

        if (isSplitDownload) {
            // —— 拆分下发:先查 DISTINCT 值,再逐值按条件切分 ——
            List<String> distinctValues = SplitFieldHelper.queryDistinctBuckets(targetTableRepository, config);
            log.info("Split[{}] distinct split values: {}", mainCommandId, distinctValues.size());
            for (String fieldValue : distinctValues) {
                // 构建按拆分字段筛选的 WHERE 条件
                String extraWhere = SplitFieldHelper.buildWhereClause(splitFields, fieldValue);
                List<Object> extraParams = SplitFieldHelper.buildParams(splitFields, fieldValue);

                // 分区表逐分区切分,否则直接切分整表
                List<String> actualTables = resolveActualTables(dbName, tableName);
                List<String> batchSpecs = new ArrayList<>();
                for (String actualTable : actualTables) {
                    splitByPlanB(mainCommandId, dbName, actualTable, pkColumns, bucketSize,
                            extraWhere, extraParams, splitFields, fieldValue, config, batchSpecs);
                }
                // 每处理完一个拆分值就 flush 桶到明细表
                flushBuckets(mainCommandId, batchSpecs, splitFields, fieldValue, config);
            }
        } else {
            // —— 单文件下发:整表直接切分 ——
            List<String> actualTables = resolveActualTables(dbName, tableName);
            List<String> batchSpecs = new ArrayList<>();
            for (String actualTable : actualTables) {
                splitByPlanB(mainCommandId, dbName, actualTable, pkColumns, bucketSize,
                        null, null, null, null, config, batchSpecs);
            }
            flushBuckets(mainCommandId, batchSpecs, null, null, config);
        }
    }

    // ==================== Plan B 逐块切分核心 ====================

    /**
     * Plan B 逐块切分循环:逐块确认 PK 边界就追加到 batchSpecs。
     * 由调用方在适当时候批量写入明细表。
     */
    private int splitByPlanB(Long mainCommandId, String dbName, String actualTable,
                              List<String> pkColumns, int bucketSize,
                              String extraWhere, List<Object> extraParams,
                              String splitField, String splitValue,
                              TransferConfig config, List<String> batchSpecs) {
        // 获取第一行 PK 值作为起始边界
        Map<String, Object> firstRow = targetTableRepository.queryNextPkBoundary(
                dbName, actualTable, pkColumns, null, 0, extraWhere, extraParams);
        if (firstRow == null) {
            log.warn("Split[{}] no data in table {}", mainCommandId, actualTable);
            return 0;
        }

        List<Object> currentStart = PkRangeUtils.extractPkValuesList(firstRow, pkColumns);
        int count = 0;

        while (true) {
            // 从 currentStart 之后跳过 bucketSize-1 行,得到桶的结束边界
            Map<String, Object> boundary = targetTableRepository.queryNextPkBoundary(
                    dbName, actualTable, pkColumns, currentStart, bucketSize - 1,
                    extraWhere, extraParams);

            String specName;
            if (boundary == null) {
                // 最后一个桶:无上界
                specName = actualTable + "|"
                        + PkRangeUtils.formatPkValues(currentStart) + "|LAST";
                batchSpecs.add(specName);
                count++;
                log.debug("Split[{}] last bucket: {}", mainCommandId, specName);
                break;
            } else {
                List<Object> nextStart = PkRangeUtils.extractPkValuesList(boundary, pkColumns);
                specName = actualTable + "|"
                        + PkRangeUtils.formatPkValues(currentStart) + "|"
                        + PkRangeUtils.formatPkValues(nextStart);
                batchSpecs.add(specName);
                count++;
                currentStart = nextStart;
            }
        }

        log.info("Split[{}] Plan B created {} buckets for {} (split={}={})",
                mainCommandId, count, actualTable, splitField, splitValue);
        return count;
    }

    /** 将一批 specName 写入明细表 */
    private void flushBuckets(Long mainCommandId, List<String> specNames,
                               String splitField, String splitValue,
                               TransferConfig config) {
        if (specNames.isEmpty()) return;
        detailRepository.createBucketsFromSpec(mainCommandId, specNames,
                splitField, splitValue,
                config.getCategoryCode(), config.getControlCode());
        specNames.clear();
    }

    /** 解析实际查询的表名列表(分区表返回分区子表,否则原表) */
    private List<String> resolveActualTables(String dbName, String tableName) {
        if (partitionHelper.isPartitioned(dbName, tableName)) {
            List<String> partitions = partitionHelper.getPartitions(dbName, tableName);
            log.debug("Resolved {} partitions for table {}", partitions.size(), tableName);
            return partitions;
        }
        return List.of(tableName);
    }

    // ==================== 合并流程 ====================

    /**
     * 启动异步合并流程。
     * <p>onSuccess / onError 由调用方(Handler)传入,包含指令表状态更新逻辑,
     * 使终态更新代码在 Handler 中可见,便于排查。</p>
     *
     * @param mainCommandId 主命令 ID
     * @param config        传输配置
     * @param baseFileInfo  基础文件路径(已解析)
     * @param onSuccess     合并成功回调(由 Handler 定义,通常置主命令为 Y)
     * @param onError       合并失败回调(由 Handler 定义,通常置主命令为 E)
     */
    public void startMergeAsync(Long mainCommandId, TransferConfig config, ResolvedPath baseFileInfo,
                                Runnable onSuccess, Runnable onError) {
        abortFlags.put(mainCommandId, false);
        long startTime = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            LogUtils.setTaskId(mainCommandId);
            try {
                boolean isSplitDownload = config.getSplitFields() != null
                        && !config.getSplitFields().isEmpty();
                boolean ok;
                if (isSplitDownload) {
                    ok = mergeSplitFiles(mainCommandId, config, baseFileInfo, startTime);
                } else {
                    ok = mergeSingleFile(mainCommandId, config, baseFileInfo, startTime);
                }
                if (ok) {
                    log.info("Merge flow completed for command: {}", mainCommandId);
                    onSuccess.run();
                } else {
                    log.warn("Merge flow aborted for command: {}", mainCommandId);
                    onError.run();
                }
            } catch (Exception e) {
                log.error("Merge flow failed for command {}: {}", mainCommandId, e.getMessage(), e);
                onError.run();
            } finally {
                abortFlags.remove(mainCommandId);
            }
        });
    }

    // ==================== 单文件下发合并 ====================

    private boolean mergeSingleFile(Long mainCommandId, TransferConfig config, ResolvedPath baseFileInfo,
                                    long startTime) {
        int timeoutHours = appConfig.getPolling().getTaskTimeoutHours();
        long timeoutMs = timeoutHours * 3600_000L;
        String ftpName = config.getFtpName();
        String targetPath = baseFileInfo.fullPath();
        String parentDir = FilePathUtils.extractParentDirectory(targetPath);
        String tempDir = (parentDir != null ? parentDir : "") + "/" + SystemConstants.TEMP_DIR_NAME;

        FieldMapping mapping = fieldMappingBuilder.buildForDownload(config);
        FileConverter converter = converterFactory.get(config.getParserType());
        int totalCount = targetTableRepository.count(config.getDbName(), config.getTableName());
        log.info("Merge[{}] single file, total count={}", mainCommandId, totalCount);

        Set<Long> mergedIds = ConcurrentHashMap.newKeySet();
        boolean headerWritten = false;
        long pollIntervalMs = SystemConstants.MERGE_POLL_INTERVAL_MS;

        while (true) {
            if (isAborted(mainCommandId)) return false;

            // 整体超时保护
            if (System.currentTimeMillis() - startTime >= timeoutMs) {
                log.warn("Merge[{}] single file timeout after {}ms (>{})h", mainCommandId,
                        System.currentTimeMillis() - startTime, timeoutHours);
                failRemaining(mainCommandId);
                return false;
            }

            // 检查 E 桶 → 终止
            if (hasErrorBuckets(mainCommandId)) {
                failRemaining(mainCommandId);
                return false;
            }

            List<Detail> ready = detailRepository.findBucketsByStatus(
                    mainCommandId, ColumnNames.STATUS_SUCCESS, 200);
            ready.removeIf(d -> mergedIds.contains(d.getId()));

            if (!ready.isEmpty()) {
                if (!headerWritten) {
                    writeHeaderToFile(ftpName, targetPath, converter, mapping, totalCount);
                    ensureParentDir(ftpName, targetPath);
                    headerWritten = true;
                }
                for (Detail d : ready) {
                    String tempFile = tempDir + "/" + d.getId() + SystemConstants.TEMP_FILE_SUFFIX;
                    // 仅当 OK 文件存在(临时文件已完整写入)时才合并
                    if (appendTempFile(ftpName, tempFile, targetPath)) {
                        deleteTempFile(ftpName, tempFile);
                        mergedIds.add(d.getId());
                    }
                }
                // 有桶可合并时立即重置轮询间隔,快速响应后续桶
                pollIntervalMs = SystemConstants.MERGE_POLL_INTERVAL_MS;
                continue;
            }

            if (isMergeDone(mainCommandId, mergedIds.size())) {
                log.info("Merge[{}] single file done, writing footer", mainCommandId);
                writeFooterToFile(ftpName, targetPath, converter, mapping);
                writeFlagFile(ftpName, config, baseFileInfo, totalCount, "U");
                writeFlagFile(ftpName, config, baseFileInfo, totalCount, "T");
                return true;
            }
            // 无桶时指数退避:3s → 6s → 12s → 24s → 30s(上限),减少空轮询
            TransferSupport.safeSleep(pollIntervalMs);
            pollIntervalMs = Math.min(pollIntervalMs * 2, 30000L);
        }
    }

    // ==================== 拆分下发合并 ====================

    private boolean mergeSplitFiles(Long mainCommandId, TransferConfig config, ResolvedPath baseFileInfo,
                                    long startTime) {
        int timeoutHours = appConfig.getPolling().getTaskTimeoutHours();
        long timeoutMs = timeoutHours * 3600_000L;
        String ftpName = config.getFtpName();
        String splitFields = config.getSplitFields();
        FieldMapping mapping = fieldMappingBuilder.buildForDownload(config);
        FileConverter converter = converterFactory.get(config.getParserType());
        Set<Long> mergedIds = ConcurrentHashMap.newKeySet();
        Set<String> finalizedTargets = ConcurrentHashMap.newKeySet();
        long pollIntervalMs = SystemConstants.MERGE_POLL_INTERVAL_MS;

        while (true) {
            if (isAborted(mainCommandId)) return false;

            // 整体超时保护
            if (System.currentTimeMillis() - startTime >= timeoutMs) {
                log.warn("Merge[{}] split files timeout after {}ms (>{}h)", mainCommandId,
                        System.currentTimeMillis() - startTime, timeoutHours);
                failRemaining(mainCommandId);
                return false;
            }

            if (hasErrorBuckets(mainCommandId)) {
                failRemaining(mainCommandId);
                return false;
            }

            List<Detail> ready = detailRepository.findBucketsByStatus(
                    mainCommandId, ColumnNames.STATUS_SUCCESS, 500);
            ready.removeIf(d -> mergedIds.contains(d.getId()));
            if (ready.isEmpty()) {
                if (isMergeDone(mainCommandId, mergedIds.size())) {
                    log.info("Merge[{}] all split files done", mainCommandId);
                    writeFlagFile(ftpName, config, baseFileInfo, 0, "T");
                    return true;
                }
                // 无桶时指数退避,减少空轮询
                TransferSupport.safeSleep(pollIntervalMs);
                pollIntervalMs = Math.min(pollIntervalMs * 2, 30000L);
                continue;
            }

            // 有桶可合并时立即重置轮询间隔
            pollIntervalMs = SystemConstants.MERGE_POLL_INTERVAL_MS;

            // 按 fieldValue 分组后并行处理各文件
            var groups = ready.stream()
                    .collect(Collectors.groupingBy(d -> d.getFieldValue() != null ? d.getFieldValue() : ""));

            groups.entrySet().parallelStream().forEach(entry -> {
                String fv = entry.getKey();
                List<Detail> group = entry.getValue();

                // 构建该组目标文件路径(复用 TransferSupport 占位符解析)
                Map<String, String> ctx = TransferSupport.splitFieldValues(splitFields, fv);
                String targetPath = transferSupport.resolveFilePath(baseFileInfo.fullPath(), ctx).fullPath();
                String parentDir = FilePathUtils.extractParentDirectory(targetPath);
                String tempDir = (parentDir != null ? parentDir : "") + "/" + SystemConstants.TEMP_DIR_NAME;

                int groupCount = targetTableRepository.countByBucket(
                        config.getDbName(), config.getTableName(), splitFields, fv);
                boolean headerWritten = fileExists(ftpName, targetPath);

                if (!headerWritten) {
                    writeHeaderToFile(ftpName, targetPath, converter, mapping, groupCount);
                    ensureParentDir(ftpName, targetPath);
                }

                for (Detail d : group) {
                    String tempFile = tempDir + "/" + d.getId() + SystemConstants.TEMP_FILE_SUFFIX;
                    if (appendTempFile(ftpName, tempFile, targetPath)) {
                        deleteTempFile(ftpName, tempFile);
                        mergedIds.add(d.getId());
                    }
                }

                // 检查该文件是否还有未合桶
                long remaining = detailRepository.findBucketsByStatus(
                                mainCommandId, ColumnNames.STATUS_SUCCESS, 1000).stream()
                        .filter(d -> !mergedIds.contains(d.getId()))
                        .filter(d -> fv.equals(d.getFieldValue() != null ? d.getFieldValue() : ""))
                        .count();

                if (remaining == 0 && finalizedTargets.add(fv)) {
                    log.info("Merge[{}] file done: {}", mainCommandId, targetPath);
                    writeFooterToFile(ftpName, targetPath, converter, mapping);
                    writeFlagFile(ftpName, config, baseFileInfo, groupCount, "U");
                }
            });
        }
    }

    // ==================== 核心 FTP 操作 ====================

    private void writeHeaderToFile(String ftpName, String path,
                                    FileConverter conv, FieldMapping mapping, int count) {
        try {
            ftpPool.withClient(ftpName, (FtpPool.FtpVoidCallback) client -> {
                String parent = FilePathUtils.extractParentDirectory(path);
                if (parent != null && !parent.isEmpty()) client.mkdirs(parent);
                try (var os = client.getOutputStream(path)) {
                    conv.writeHeader(os, mapping, count);
                }
                client.completePendingCommand();
            });
        } catch (Exception e) {
            log.warn("Failed to write header to {}: {}", path, e.getMessage());
        }
    }

    private boolean appendTempFile(String ftpName, String tempPath, String targetPath) {
        try {
            return ftpPool.withClient(ftpName, (FtpPool.FtpCallback<Boolean>) client -> {
                if (!client.exists(tempPath)) {
                    log.debug("Temp file not ready: {}", tempPath);
                    return false;
                }
                try (var in = client.getInputStream(tempPath)) {
                    client.append(targetPath, in);
                }
                return true;
            });
        } catch (Exception e) {
            log.warn("Failed to append {} to {}: {}", tempPath, targetPath, e.getMessage());
            return false;
        }
    }

    private void writeFooterToFile(String ftpName, String path,
                                    FileConverter conv, FieldMapping mapping) {
        try {
            byte[] footerBytes;
            try (var baos = new ByteArrayOutputStream(64)) {
                conv.writeFooter(baos, mapping);
                footerBytes = baos.toByteArray();
            }
            if (footerBytes.length == 0) return;
            try (var in = new ByteArrayInputStream(footerBytes)) {
                ftpPool.withClient(ftpName, (FtpPool.FtpVoidCallback) client -> {
                    client.append(path, in);
                });
            }
        } catch (Exception e) {
            log.warn("Failed to write footer to {}: {}", path, e.getMessage());
        }
    }

    private void ensureParentDir(String ftpName, String path) {
        String parent = FilePathUtils.extractParentDirectory(path);
        if (parent == null || parent.isEmpty()) return;
        try {
            ftpPool.withClient(ftpName, (FtpPool.FtpVoidCallback) client -> client.mkdirs(parent));
        } catch (Exception e) {
            log.debug("ensureParentDir: {}", e.getMessage());
        }
    }

    private void writeFlagFile(String ftpName, TransferConfig config,
                                ResolvedPath fileInfo, int count, String type) {
        String postOps = config.getPostOperations();
        if (postOps == null || !postOps.contains(type)) return;
        try {
            String ops = com.fmsy.fileops.FlagFileService.filterOpsByType(postOps, type);
            if (ops != null) {
                ftpPool.withClient(ftpName, (FtpPool.FtpVoidCallback) client -> {
                    transferSupport.postProcess(client, ops, fileInfo,
                            Map.of("C", String.valueOf(count)));
                });
            }
        } catch (Exception e) {
            log.warn("Failed to write {} flag: {}", type, e.getMessage());
        }
    }

    private boolean fileExists(String ftpName, String path) {
        try {
            return ftpPool.withClient(ftpName, (FtpPool.FtpCallback<Boolean>) client -> client.exists(path));
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteTempFile(String ftpName, String path) {
        try {
            ftpPool.withClient(ftpName, (FtpPool.FtpVoidCallback) client -> client.deleteFile(path));
        } catch (Exception e) {
            log.debug("Failed to delete temp {}: {}", path, e.getMessage());
        }
    }

    // ==================== 状态检查 ====================

    private boolean hasErrorBuckets(Long cmdId) {
        return !detailRepository.findBucketsByStatus(cmdId, ColumnNames.STATUS_ERROR, 1).isEmpty();
    }

    private boolean isMergeDone(Long cmdId, int mergedCount) {
        long[] counts = detailRepository.countMergeStatus(cmdId);
        long empty = counts[0], proc = counts[1], totalY = counts[2];
        if (empty > 0 || proc > 0) return false;
        if (!commandRepository.isSplitDone(cmdId)) return false;
        return totalY <= mergedCount;
    }

    private void failRemaining(Long cmdId) {
        abortFlags.put(cmdId, true);
        int n = detailRepository.batchUpdateStatus(cmdId, ColumnNames.STATUS_EMPTY, ColumnNames.STATUS_SKIPPED);
        log.warn("Merge[{}] abort, skipped {} buckets", cmdId, n);
    }

    private boolean isAborted(Long cmdId) {
        return Boolean.TRUE.equals(abortFlags.get(cmdId));
    }
}