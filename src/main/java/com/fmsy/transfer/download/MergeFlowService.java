package com.fmsy.transfer.download;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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

    private volatile boolean abortRequested = false;

    /**
     * 启动异步合并流程。
     *
     * @param mainCommandId 主命令 ID
     * @param config        传输配置
     * @param baseFileInfo  基础文件路径(已解析)
     */
    public void startMergeAsync(Long mainCommandId, TransferConfig config, ResolvedPath baseFileInfo) {
        CompletableFuture.runAsync(() -> {
            LogUtils.setTaskId(mainCommandId);
            try {
                boolean isSplitDownload = config.getSplitFields() != null
                        && !config.getSplitFields().isEmpty();
                if (isSplitDownload) {
                    mergeSplitFiles(mainCommandId, config, baseFileInfo);
                } else {
                    mergeSingleFile(mainCommandId, config, baseFileInfo);
                }
                log.info("Merge flow completed for command: {}", mainCommandId);
            } catch (Exception e) {
                log.error("Merge flow failed for command {}: {}", mainCommandId, e.getMessage(), e);
            }
        });
    }

    // ==================== 单文件下发 ====================

    private void mergeSingleFile(Long mainCommandId, TransferConfig config, ResolvedPath baseFileInfo) {
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

        while (true) {
            if (abortRequested) break;

            // 检查 E 桶 → 终止
            if (hasErrorBuckets(mainCommandId)) {
                failRemaining(mainCommandId);
                return;
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
                continue;
            }

            if (isMergeDone(mainCommandId, mergedIds.size())) {
                log.info("Merge[{}] single file done, writing footer", mainCommandId);
                writeFooterToFile(ftpName, targetPath, converter, mapping);
                writeFlagFile(ftpName, config, baseFileInfo, totalCount, "SUB");
                writeFlagFile(ftpName, config, baseFileInfo, totalCount, "TOTAL");
                updateMainStatus(mainCommandId, ColumnNames.STATUS_SUCCESS);
                return;
            }
            safeSleep(SystemConstants.MERGE_POLL_INTERVAL_MS);
        }
    }

    // ==================== 拆分下发 ====================

    private void mergeSplitFiles(Long mainCommandId, TransferConfig config, ResolvedPath baseFileInfo) {
        String ftpName = config.getFtpName();
        String splitFields = config.getSplitFields();
        FieldMapping mapping = fieldMappingBuilder.buildForDownload(config);
        FileConverter converter = converterFactory.get(config.getParserType());
        Set<Long> mergedIds = ConcurrentHashMap.newKeySet();
        Set<String> finalizedTargets = ConcurrentHashMap.newKeySet();

        while (true) {
            if (abortRequested) break;

            if (hasErrorBuckets(mainCommandId)) {
                failRemaining(mainCommandId);
                return;
            }

            List<Detail> ready = detailRepository.findBucketsByStatus(
                    mainCommandId, ColumnNames.STATUS_SUCCESS, 500);
            ready.removeIf(d -> mergedIds.contains(d.getId()));
            if (ready.isEmpty()) {
                if (isMergeDone(mainCommandId, mergedIds.size())) {
                    log.info("Merge[{}] all split files done", mainCommandId);
                    writeFlagFile(ftpName, config, baseFileInfo, 0, "TOTAL");
                    updateMainStatus(mainCommandId, ColumnNames.STATUS_SUCCESS);
                    return;
                }
                safeSleep(SystemConstants.MERGE_POLL_INTERVAL_MS);
                continue;
            }

            // 按 fieldValue 分组
            var groups = ready.stream()
                    .collect(Collectors.groupingBy(d -> d.getFieldValue() != null ? d.getFieldValue() : ""));

            for (var entry : groups.entrySet()) {
                String fv = entry.getKey();
                List<Detail> group = entry.getValue();

                // 构建该组目标文件路径
                String targetPath = resolveTargetPath(baseFileInfo.fullPath(), splitFields, fv);
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
            }
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
     * <p>先检查对应的 .ok 哨兵文件是否存在,确保子节点已完整写入临时文件后才合并,
     * 防止读取写入中的不完整文件。</p>
     *
     * @return true=合并成功; false=临时文件未就绪(ok 文件不存在)或合并失败
     */
    private boolean appendTempFile(String ftpName, String tempPath, String targetPath) {
        try {
            return ftpPool.withClient(ftpName, (FtpPool.FtpCallback<Boolean>) client -> {
                // 检查 OK 哨兵文件 — 子节点写完 .tmp 后再写 .ok
                String okPath = toOkPath(tempPath);
                if (!client.exists(okPath)) {
                    log.debug("Temp file not ready (no .ok): {}", tempPath);
                    return false;
                }
                if (!client.exists(tempPath)) {
                    log.warn("Temp file missing (ok exists but tmp gone): {}", tempPath);
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

    /** 删除 FTP 临时文件及对应的 OK 哨兵文件 */
    private void deleteTempFile(String ftpName, String path) {
        // 先删 .ok 哨兵文件
        String okPath = toOkPath(path);
        try {
            ftpPool.withClient(ftpName, (FtpPool.FtpVoidCallback) client -> client.deleteFile(okPath));
        } catch (Exception e) {
            log.debug("Failed to delete ok {}: {}", okPath, e.getMessage());
        }
        // 再删 .tmp 临时文件
        try {
            ftpPool.withClient(ftpName, (FtpPool.FtpVoidCallback) client -> client.deleteFile(path));
        } catch (Exception e) {
            log.debug("Failed to delete temp {}: {}", path, e.getMessage());
        }
    }

    // ==================== 文件路径工具 ====================

    /** 将 .tmp 路径转换为对应的 .ok 路径 */
    private static String toOkPath(String tempPath) {
        return tempPath.substring(0, tempPath.length() - SystemConstants.TEMP_FILE_SUFFIX.length())
                + SystemConstants.OK_FILE_SUFFIX;
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
        int empty = detailRepository.countByStatus(cmdId, ColumnNames.STATUS_EMPTY);
        int proc = detailRepository.countByStatus(cmdId, ColumnNames.STATUS_PROCESSING);
        if (empty > 0 || proc > 0) return false;
        if (!commandRepository.isSplitDone(cmdId)) return false;
        // 兜底检查:确保没有遗漏的 Y 桶(极端竞态 — 子节点刚好写 Y 但查询在之后)
        int totalY = detailRepository.countByStatus(cmdId, ColumnNames.STATUS_SUCCESS);
        return totalY <= mergedCount;
    }

    /** 终止合并:标记未处理桶为跳过 */
    private void failRemaining(Long cmdId) {
        abortRequested = true;
        int n = detailRepository.batchUpdateStatus(cmdId, ColumnNames.STATUS_EMPTY, ColumnNames.STATUS_SKIPPED);
        log.warn("Merge[{}] abort, skipped {} buckets", cmdId, n);
        updateMainStatus(cmdId, ColumnNames.STATUS_ERROR);
    }

    private void updateMainStatus(Long cmdId, String status) {
        commandRepository.updateStatus(cmdId, status);
        log.info("Merge[{}] main command status -> {}", cmdId, status);
    }

    // ==================== 工具 ====================

    /** 拆分字段值 → 目标文件路径(占位符替换) */
    private static String resolveTargetPath(String template, String splitFields, String fieldValue) {
        if (splitFields == null || fieldValue == null) return template;
        String[] names = splitFields.split(",");
        String[] values = fieldValue.split(",");
        for (int i = 0; i < names.length && i < values.length; i++) {
            template = template.replace("{" + names[i].trim() + "}", values[i].trim());
        }
        return template;
    }

    private static void safeSleep(long ms) {
        try { TimeUnit.MILLISECONDS.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
