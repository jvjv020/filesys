package com.fmsy.transfer.upload;

import com.fmsy.fileops.FlagFileService;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.BooleanUtils;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/**
 * UPLOAD_MULTI 场景的 SERIAL 模式：通配符匹配目录所有文件，
 * 单次 FTP 连接内预扫描后并行上传到同一张表。
 *
 * <p>
 * 配置路径中的 {@code {FILE_NAME}} 占位符替换为 {@code *}（通配符），
 * 用于 FTP 文件列表匹配。
 * </p>
 * <p>
 * 示例：{@code /data/input/{YYYYMMDD}/BR{FILE_NAME}.csv}
 * → {@code *} 匹配所有 BR 开头的 csv 文件。
 * </p>
 *
 * <p>
 * 阶段顺序：<br>
 * 预扫描（单次 FTP 列表 → {@link UploadPrescanner} 筛选）→ 清表 → 并行落库 → 汇总
 *
 * <p>
 * 前稽核已合并到 insertDataAndVerify 的落库后阶段，与后审计使用
 * CloseableIterator.getRecordCount() 统一校验，无需额外 FTP 流做独立前稽核。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiUploadHandler implements TransferHandler {

    /** insertSingleFile 失败标记值 */
    private static final int TASK_FAIL = -2;

    private final UploadSupport support;
    private final TransferSupport transferSupport;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final IntFunction<ExecutorService> batchExecutorFactory;

    /**
     * <ol>
     * <li>解析配置路径，{FILE_NAME} → *，在单次 FTP 连接内完成列表 + 标志检查 + 异常文件处理</li>
     * <li>统一清表（clearTableFlag=Y 时）</li>
     * <li>对有效文件并行执行落库（insertDataAndVerify + postProcess），
     * 前稽核与后审计在落库后统一校验</li>
     * <li>汇总后操作（postProcess）</li>
     * </ol>
     *
     * <p>
     * 路径配置示例：{@code /data/input/{YYYYMMDD}/BR{FILE_NAME}.csv}
     * — SERIAL 模式将 {FILE_NAME} 替换为 {@code *}，匹配所有 BR 开头的 csv 文件。
     * </p>
     */
    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Multi-file upload from directory, command={}, table={}",
                command.getId(), config.getTableName());
        String ftpName = config.getFtpName();

        // 解析路径中的 {YYYYMMDD} 等占位符，保留 ResolvedPath 供后操作回退
        ResolvedPath resolvedDir = transferSupport.resolveFilePath(config.getFilePath(), command);
        String resolvedPath = resolvedDir != null ? resolvedDir.fullPath() : null;
        // {FILE_NAME} → *（通配符匹配），用于 FTP 文件列表
        String listPattern = resolvedPath != null ? resolvedPath.replace("{FILE_NAME}", "*") : null;

        /* ---------- Phase 1: 单次 FTP 连接内完成列表 + 标志检查 + 异常文件处理 ---------- */
        List<String> validFiles = prescanAndValidate(ftpName, listPattern, config);
        if (validFiles == null) {
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Failed to list directory");
            return;
        }
        if (validFiles.isEmpty()) {
            log.info("No valid data files after prescan (pattern: {})", listPattern);
            result.setOutcome(0, TransferSupport.determineMainStatus(false, 0, 1), "");
            return;
        }
        log.info("Prescan: {} valid data files (pattern: {})", validFiles.size(), listPattern);

        /* ---------- Phase 2: 清表 ---------- */
        if (BooleanUtils.isYes(config.getClearTableFlag())) {
            support.truncateTable(config);
        }

        /* ---------- Phase 3: 并行落库 ---------- */
        int concurrency = config.getConcurrency() != null ? config.getConcurrency() : 3;
        int failedCount = 0;
        FieldMapping sharedMapping = fieldMappingBuilder.buildForUpload(config, null);

        ExecutorService insertExecutor = batchExecutorFactory.apply(concurrency);
        List<Future<Integer>> insertFutures = new ArrayList<>();
        for (String filePath : validFiles) {
            insertFutures.add(insertExecutor.submit(
                    () -> insertSingleFile(ftpName, filePath, ResolvedPath.of(filePath),
                            config, sharedMapping, null)));
        }
        TransferSupport.shutdownExecutor(insertExecutor, 1, TimeUnit.MINUTES, "Upload executor");

        // 收集落库结果
        int totalRecords = 0;
        for (int i = 0; i < insertFutures.size(); i++) {
            try {
                Integer recordCount = insertFutures.get(i).get(30, TimeUnit.MINUTES);
                if (recordCount != null && recordCount > 0) {
                    totalRecords += recordCount;
                } else {
                    failedCount++;
                }
            } catch (Exception e) {
                log.error("Insert failed for file: {}", validFiles.get(i), e);
                failedCount++;
            }
        }

        // 有文件失败时仅日志告警，不清表不回滚（清表已前置，单文件失败已在各自事务中回滚）
        if (failedCount > 0) {
            log.warn("{} file(s) failed, individual transactions rolled back", failedCount);
        }

        result.setOutcome(totalRecords, ColumnNames.STATUS_SUCCESS, "");
    }

    // ==================== 落库阶段（单文件） ====================

    /**
     * 对单个文件执行落库操作（insertDataAndVerify + postProcess）。
     *
     * <p>
     * SERIAL 模式（目录通配符）下 auditCount 传入 null，跳过稽核数校验。
     * </p>
     *
     * @param mapping    预构建的 FieldMapping（所有文件共享，避免重复构建）
     * @param auditCount 稽核数（SERIAL 模式传 null）
     * @return 成功插入的记录数；TASK_FAIL 表示失败
     */
    private Integer insertSingleFile(String ftpName, String filePath, ResolvedPath fileInfo,
            TransferConfig config, FieldMapping mapping, Integer auditCount) {
        var r = support.safeExecuteFilePipeline(
                ftpName, filePath, fileInfo, config,
                mapping, null, auditCount);
        if (ColumnNames.STATUS_ERROR.equals(r.status())) return TASK_FAIL;
        return r.records(); // 0 for SKIPPED, >0 for SUCCESS
    }

    // ==================== 预扫描（委托给 UploadPrescanner） ====================

    /**
     * 单次 FTP 连接内完成：文件列表 + 预扫描筛选。
     *
     * <p>
     * 委托给 {@link UploadPrescanner#prescanDataFiles} 完成有效数据文件筛选和孤立标志文件迁移。
     * </p>
     *
     * @param ftpName     FTP 连接名
     * @param listPattern 文件列表通配符模式（{FILE_NAME} 已替换为 *）
     * @param config      传输配置
     * @return 有效数据文件完整路径列表；null 表示列表失败
     */
    private List<String> prescanAndValidate(String ftpName, String listPattern,
            TransferConfig config) throws Exception {
        return transferSupport.executeWithClient(ftpName, client -> {
            String[] allFiles = client.listFiles(listPattern);
            if (allFiles == null) return null;
            if (allFiles.length == 0) return List.of();

            String flagPattern = FlagFileService.extractFlagPathPattern(config.getPreOperations());
            Pattern listRegex = UploadPrescanner.globToRegex(listPattern);
            List<String> validFiles = UploadPrescanner.prescanDataFiles(
                    allFiles, flagPattern, listRegex, client);

            log.info("Prescan result: {} valid files (pattern: {})", validFiles.size(), listPattern);
            return validFiles;
        });
    }

}