package com.fmsy.transfer.download;

import com.fmsy.config.AppConfig;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Detail;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 合并流程服务 — 将子节点生成的临时文件按序合并到目标文件。
 *
 * <p>主节点异步执行。分两种模式:
 * <ul>
 *   <li><b>拆分下发</b>:按 fieldValue 分组,不同文件并发合并,同文件串行 APPE</li>
 *   <li><b>单文件下发</b>:所有临时文件串行 APPE 到同一个目标文件</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MergeFlowService {

    private final DetailRepository detailRepository;
    private final CommandRepository commandRepository;
    private final TargetTableRepository targetTableRepository;
    private final FtpPool ftpPool;
    private final TransferSupport transferSupport;
    private final ConverterFactory converterFactory;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final AppConfig appConfig;

    /** 每命令的终止标志 — 避免多命令间共享 volatile 字段造成误中断 */
    private final ConcurrentHashMap<Long, Boolean> abortFlags = new ConcurrentHashMap<>();

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

    // ==================== 单文件下发 ====================

    /**
     * @param startTime 合并启动时间(System.currentTimeMillis),用于超时判断
     * @return true=合并成功(所有桶已被 APPE 合并); false=合并终止(发现 E 桶或被中止)
     */
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

            // 整体超时保护:防止子节点崩溃或桶卡 P 时合并循环永远运行
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
                    // OK 文件未就绪的桶留待下次轮询
                }
                // 有桶可合并时立即重置轮询间隔,快速响应后续桶
                pollIntervalMs = SystemConstants.MERGE_POLL_INTERVAL_MS;
                continue;
            }

            if (isMergeDone(mainCommandId, mergedIds.size())) {
                log.info("Merge[{}] single file done, writing footer", mainCommandId);
                writeFooterToFile(ftpName, targetPath, converter, mapping);
                writeFlagFile(ftpName, config, baseFileInfo, totalCount, "SUB");
                writeFlagFile(ftpName, config, baseFileInfo, totalCount, "TOTAL");
                return true;
            }
            // 无桶时指数退避:3s → 6s → 12s → 24s → 30s(上限),减少空轮询
            TransferSupport.safeSleep(pollIntervalMs);
            pollIntervalMs = Math.min(pollIntervalMs * 2, 30000L);
        }
    }

    // ==================== 拆分下发 ====================

    /**
     * @param startTime 合并启动时间(System.currentTimeMillis),用于超时判断
     * @return true=合并成功(所有拆分文件已被 APPE 合并); false=合并终止(发现 E 桶或被中止)
     */
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
                    writeFlagFile(ftpName, config, baseFileInfo, 0, "TOTAL");
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
            // 不同 fieldValue 对应不同的目标文件,无冲突,可并行 APPE
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
                    // 仅当 OK 文件存在(临时文件已完整写入)时才合并
                    if (appendTempFile(ftpName, tempFile, targetPath)) {
                        deleteTempFile(ftpName, tempFile);
                        mergedIds.add(d.getId());
                    }
                    // OK 文件未就绪的桶留待下次轮询
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
                    writeFlagFile(ftpName, config, baseFileInfo, groupCount, "SUB");
                }
            });
        }
    }

    // ==================== 核心FTP操作 ====================

    /** 写文件头到目标文件 */
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

    /**
     * APPE 临时文件到目标文件。
     *
     * <p>先检查临时文件是否存在,确保子节点已写入后再合并。</p>
     *
     * @return true=合并成功; false=临时文件不存在或合并失败
     */
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

    /** 写文件尾(通过 APPE 追加) */
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

    /** 确保目标文件父目录存在 */
    private void ensureParentDir(String ftpName, String path) {
        String parent = FilePathUtils.extractParentDirectory(path);
        if (parent == null || parent.isEmpty()) return;
        try {
            ftpPool.withClient(ftpName, (FtpPool.FtpVoidCallback) client -> client.mkdirs(parent));
        } catch (Exception e) {
            log.debug("ensureParentDir: {}", e.getMessage());
        }
    }

    /** 写标志文件(SUB / TOTAL) */
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

    /** 检查 FTP 文件是否存在 */
    private boolean fileExists(String ftpName, String path) {
        try {
            return ftpPool.withClient(ftpName, (FtpPool.FtpCallback<Boolean>) client -> client.exists(path));
        } catch (Exception e) {
            return false;
        }
    }

    /** 删除 FTP 临时文件 */
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

    /**
     * 检查合并是否完成。
     * 条件:无空桶 + 无处理中桶 + 拆分已完成 + 无遗漏 Y 桶。
     *
     * @param mergedCount 已合并的桶数(mergedIds.size())
     */
    private boolean isMergeDone(Long cmdId, int mergedCount) {
        long[] counts = detailRepository.countMergeStatus(cmdId);
        long empty = counts[0], proc = counts[1], totalY = counts[2];
        if (empty > 0 || proc > 0) return false;
        if (!commandRepository.isSplitDone(cmdId)) return false;
        // 兜底检查:确保没有遗漏的 Y 桶(极端竞态 — 子节点刚好写 Y 但查询在之后)
        return totalY <= mergedCount;
    }

    /**
     * 终止合并:标记未处理桶为跳过。
     * 注意:此处只做合并内部清理(终止标志 + 桶跳过),
     * 指令表状态(E)由 Handler 传入的 onError 回调处理。
     */
    private void failRemaining(Long cmdId) {
        abortFlags.put(cmdId, true);
        int n = detailRepository.batchUpdateStatus(cmdId, ColumnNames.STATUS_EMPTY, ColumnNames.STATUS_SKIPPED);
        log.warn("Merge[{}] abort, skipped {} buckets", cmdId, n);
    }

    // ==================== 工具 ====================

    /** 检查该命令是否被标记为终止 */
    private boolean isAborted(Long cmdId) {
        return Boolean.TRUE.equals(abortFlags.get(cmdId));
    }

    }
