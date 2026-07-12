package com.fmsy.transfer.download;

import com.fmsy.audit.AuditScenario;
import com.fmsy.audit.AuditService;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Detail;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.BooleanUtils;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.FilePathUtils;
import com.fmsy.util.ResolvedPath;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;

/**
 * 下载场景 Support — 跨 3 个 Download Handler 共享的下载协议方法 + 单文件下载管线。
 *
 * <p>主要职责:
 * <ul>
 *   <li>预审计 / 后审计(DB 记录数 vs 文件行数验证)</li>
 *   <li>单文件下载管线(前操作 → 前稽核 → 生成文件 → 后稽核 → 后操作)</li>
 *   <li>覆盖检查 / 回滚等工具方法</li>
 * </ul>
 *
 * <p>管线通过 {@link #executePipeline(String, TransferConfig, ResolvedPath, PipelineOptions)}
 * 暴露,由三个 Handler 按场景配置调用:
 * <ul>
 *   <li>{@link SingleDownloadHandler} — 整表模式,开启全部阶段</li>
 *   <li>{@link SingleNodeDownloadHandler} — 分桶模式,跳过前操作/后稽核(聚合层做)</li>
 *   <li>{@link com.fmsy.polling.DetailPollingService} — 分桶模式,开启前操作+后稽核</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadSupport {

    private final AuditService auditService;
    private final TargetTableRepository targetTableRepository;
    private final DetailRepository detailRepository;
    private final FtpPool ftpPool;
    private final TransferSupport transferSupport;
    private final ConverterFactory converterFactory;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final ParallelFileGenerator parallelFileGenerator;

    // ==================== 单文件下载管线 ====================

    /**
     * 执行单文件下载管线: 前稽核 → 空数据处理 → (FTP 会话: 前操作 → 生成文件 → 后操作) → 后稽核。
     *
     * <p>FTP 客户端生命周期由管线内部管理,调用方只需提供选项。
     *
     * @param ftpName    FTP 服务器名称
     * @param config     传输配置
     * @param targetFile 预解析的目标文件路径
     * @param opts       管线选项
     * @return 管线执行结果
     */
    public PipelineResult executePipeline(String ftpName, TransferConfig config,
                                          ResolvedPath targetFile, PipelineOptions opts) {
        String filePath = targetFile != null ? targetFile.fullPath() : null;
        log.debug("Pipeline start: ftp={}, path={}, wholeTable={}, fieldValue={}",
                ftpName, filePath, opts.wholeTable, opts.fieldValue);

        // ---- Phase 1: 前稽核 (DB-only) ----
        int recordCount;
        if (opts.expectedAuditCount != null && opts.expectedAuditCount >= 0) {
            if (opts.wholeTable) {
                recordCount = preAudit(config, opts.expectedAuditCount);
            } else {
                recordCount = preAuditByBucket(config, opts.expectedAuditCount, opts.fieldValue);
            }
            if (recordCount < 0) {
                log.warn("Pre-audit failed for {}, auditCount={}", filePath, opts.expectedAuditCount);
                updateDetailStatus(opts, ColumnNames.STATUS_SKIPPED);
                return new PipelineResult(0, false, ColumnNames.STATUS_SKIPPED, filePath);
            }
        } else {
            if (opts.wholeTable) {
                recordCount = countRecords(config);
            } else {
                recordCount = targetTableRepository.countByBucket(
                        config.getDbName(), config.getTableName(),
                        config.getSplitFields(), opts.fieldValue);
            }
        }

        // ---- Phase 2: 空数据处理 ----
        if (recordCount == 0 && !transferSupport.handleEmptyData(0, config.getEmptyDataHandling())) {
            String status = config.getEmptyDataHandling() == EmptyDataHandling.ERROR
                    ? ColumnNames.STATUS_ERROR : ColumnNames.STATUS_SKIPPED;
            log.warn("Empty data handling ({}) for {}", config.getEmptyDataHandling(), filePath);
            updateDetailStatus(opts, status);
            return new PipelineResult(0, false, status, filePath);
        }

        // ---- Phase 3: FTP 传输 (单个会话) ----
        FtpClient client = null;
        try {
            client = ftpPool.getClient(ftpName);

            // 3a: 前操作
            if (opts.enablePreCheck) {
                if (!transferSupport.preCheck(client, config, targetFile)) {
                    log.warn("Pre-check failed for {}", filePath);
                    updateDetailStatus(opts, ColumnNames.STATUS_SKIPPED);
                    return new PipelineResult(0, false, ColumnNames.STATUS_SKIPPED, filePath);
                }
                String parentDir = FilePathUtils.extractParentDirectory(filePath);
                if (parentDir != null && !parentDir.isEmpty()) {
                    client.mkdirs(parentDir);
                }
            }

            // 3b: 覆盖检查(可选)
            if (opts.enableOverwriteCheck) {
                if (!checkOverwriteAllowed(client, filePath, config.getOverwriteFlag())) {
                    log.warn("Overwrite denied for {}", filePath);
                    updateDetailStatus(opts, ColumnNames.STATUS_ERROR);
                    return new PipelineResult(0, false, ColumnNames.STATUS_ERROR, filePath);
                }
            }

            // 3c: 生成文件
            FileConverter converter = converterFactory.get(config.getParserType());
            FieldMapping mapping = fieldMappingBuilder.buildForDownload(config);

            int count;
            try (OutputStream os = client.getOutputStream(filePath)) {
                if (opts.wholeTable) {
                    count = parallelFileGenerator.generate(
                            os, config, converter, mapping, recordCount);
                } else {
                    try (var data = targetTableRepository.streamBucketData(
                            config.getDbName(), config.getTableName(),
                            config.getSplitFields(), opts.fieldValue)) {
                        converter.generate(os, data, mapping);
                    }
                    count = recordCount;
                }
                client.completePendingCommand();
            }

            // 3d: 后操作
            String postOps = resolvePostOps(config, opts);
            if (postOps != null && !postOps.isEmpty()) {
                transferSupport.postProcess(client, postOps, targetFile, count);
            }

            client.close();
            client = null;

            // ---- Phase 4: 后稽核 ----
            if (opts.enablePostAudit) {
                if (!postAudit(config, filePath, count)) {
                    log.error("Post-audit failed for {}, rolling back file", filePath);
                    rollbackFile(ftpName, filePath);
                    updateDetailStatus(opts, ColumnNames.STATUS_ERROR);
                    return new PipelineResult(count, false, ColumnNames.STATUS_ERROR, filePath);
                }
            }

            // ---- Phase 5: 更新 Detail 状态 ----
            updateDetailStatus(opts, ColumnNames.STATUS_SUCCESS);

            log.info("Pipeline completed: {}, records={}", filePath, count);
            return new PipelineResult(count, true, ColumnNames.STATUS_SUCCESS, filePath);

        } catch (Exception e) {
            log.error("Pipeline failed for {}: {}", filePath, e.getMessage(), e);
            updateDetailStatus(opts, ColumnNames.STATUS_ERROR);
            return new PipelineResult(0, false, ColumnNames.STATUS_ERROR, filePath);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /** 解析后操作字符串:按 postOpsFilter 过滤 config 的后操作列表。 */
    private String resolvePostOps(TransferConfig config, PipelineOptions opts) {
        String postOps = config.getPostOperations();
        if (postOps == null || postOps.isEmpty()) {
            return null;
        }
        if (opts.postOpsFilter != null && !opts.postOpsFilter.isEmpty()) {
            return com.fmsy.fileops.FlagFileService.filterOpsByType(postOps, opts.postOpsFilter);
        }
        return postOps;
    }

    /** 后审计失败时回滚 FTP 文件 — best-effort 删除。 */
    private void rollbackFile(String ftpName, String filePath) {
        try {
            transferSupport.executeWithClient(ftpName, client -> {
                rollbackAfterPostAuditFailure(client, filePath, "pipeline post-audit");
                return null;
            });
        } catch (Exception e) {
            log.warn("Rollback failed for {}: {}", filePath, e.getMessage());
        }
    }

    /** 更新明细表状态,仅在 detail 有 id 时执行。 */
    private void updateDetailStatus(PipelineOptions opts, String status) {
        if (opts.detail == null || opts.detail.getId() == null) {
            return;
        }
        try {
            detailRepository.updateStatus(opts.detail.getId(), status, opts.nodeId);
        } catch (Exception e) {
            log.error("Failed to update detail status for id={}, status={}: {}",
                    opts.detail.getId(), status, e.getMessage());
        }
    }

    // ==================== 预审计 / 后审计 ====================

    /**
     * 预审计:DB 记录数与 auditCount 比较(DOWNLOAD 模式)
     *
     * @return 实际 DB 记录数(审计通过时); -1(审计不通过)
     */
    public int preAudit(TransferConfig config, int auditCount) {
        return auditService.preAudit(AuditScenario.DOWNLOAD, config.getTableName(), auditCount,
                config.getDbName());
    }

    /**
     * 分桶预审计:DB 桶范围 COUNT 与 auditCount 比较
     *
     * @return 实际桶记录数(审计通过时); -1(审计不通过)
     */
    public int preAuditByBucket(TransferConfig config, int auditCount, String fieldValue) {
        return auditService.preAuditByBucket(config.getTableName(),
                config.getSplitFields(), fieldValue, auditCount, config.getDbName());
    }

    /**
     * 后审计:文件行数与 DB 记录数比较(DOWNLOAD 模式)。
     * 内部会重新 COUNT 全表。
     */
    public boolean postAudit(TransferConfig config, String filePath) {
        return auditService.postAudit(AuditScenario.DOWNLOAD, config.getFtpName(),
                config.getTableName(), filePath, -1, config.getDbName());
    }

    /**
     * 后审计:文件行数与已知 DB 记录数比较,避免内部重复 COUNT。
     */
    public boolean postAudit(TransferConfig config, String filePath, int knownDbCount) {
        return auditService.postAudit(AuditScenario.DOWNLOAD, config.getFtpName(),
                config.getTableName(), filePath, knownDbCount, config.getDbName());
    }

    public int countRecords(TransferConfig config) {
        return targetTableRepository.count(config.getDbName(), config.getTableName());
    }

    // ==================== 工具方法 ====================

    /**
     * 覆盖检查(迭代 #8):目标文件已存在且配置不允许覆盖时,
     * 检查同目录是否存在 *.FLG / *.DONE / *.READY / *.OK 等"完成标志"。
     */
    public boolean checkOverwriteAllowed(FtpClient client, String filePath, String overwriteFlag) {
        if (overwriteFlag == null || BooleanUtils.isYes(overwriteFlag)) {
            return true;
        }
        if (filePath == null) {
            return true;
        }
        if (!client.exists(filePath)) {
            return true;
        }
        int slashIdx = filePath.lastIndexOf('/');
        String baseName = slashIdx >= 0 ? filePath.substring(slashIdx + 1) : filePath;
        int dot = baseName.lastIndexOf('.');
        String stem = dot > 0 ? baseName.substring(0, dot) : baseName;
        String dir = slashIdx >= 0 ? filePath.substring(0, slashIdx) : "";
        String[] flagSuffixes = { ".FLG", ".DONE", ".READY", ".OK" };
        for (String suffix : flagSuffixes) {
            String flagPath = (dir.isEmpty() ? "" : dir + "/") + stem + suffix;
            if (client.exists(flagPath)) {
                log.warn("Overwrite denied: target {} exists with completion flag {}", filePath, flagPath);
                return false;
            }
        }
        return true;
    }

    /**
     * P0 #1:根据桶/文件汇总结果判定主指令最终状态。
     */
    public static String determineMainStatus(boolean allSuccess, int failedCount, int skippedCount) {
        return TransferSupport.determineMainStatus(allSuccess, failedCount, skippedCount);
    }

    /**
     * 后审计失败回滚 - 删除 FTP 上已生成的目标文件。
     * best-effort:删除失败仅记 warn,不抛异常。
     */
    public static boolean rollbackAfterPostAuditFailure(FtpClient client, String filePath, String reason) {
        if (client == null || filePath == null || filePath.isEmpty()) {
            return false;
        }
        try {
            boolean deleted = client.deleteFile(filePath);
            if (deleted) {
                log.error("Post-audit failed ({}), rolled back FTP file: {}", reason, filePath);
            } else {
                log.error("Post-audit failed ({}), FTP file does not exist or delete denied: {}",
                        reason, filePath);
            }
            return deleted;
        } catch (Exception e) {
            log.error("Failed to delete FTP file {} during post-audit rollback: {}",
                    filePath, e.getMessage());
            return false;
        }
    }

    // ==================== 值类型(管线结果 / 选项) ====================

    /** 管线执行结果。 */
    @Value
    public static class PipelineResult {
        int recordCount;
        boolean success;
        String status;
        String filePath;
    }

    /** 管线选项 — 控制数据源、阶段开关、后操作过滤和 Detail 状态更新。 */
    @Data
    @Builder
    public static class PipelineOptions {

        // ========== 数据源 ==========

        /** true=ParallelFileGenerator(整表), false=streamBucketData(分桶) */
        boolean wholeTable;

        /** 分桶字段值(wholeTable=false 时使用) */
        String fieldValue;

        // ========== 稽核 ==========

        /**
         * 预期稽核数,非 null 且 ≥0 时执行前稽核(与 DB 实际记录数比较);
         * null 时跳过前稽核,仅计数。
         */
        Integer expectedAuditCount;

        // ========== 阶段控制 ==========

        /** 前操作(READY/FLAG 检查 + 目录创建). 默认 true */
        @Builder.Default
        boolean enablePreCheck = true;

        /** 覆盖检查. 默认 false(仅 DOWNLOAD_SINGLE 需要) */
        @Builder.Default
        boolean enableOverwriteCheck = false;

        /** 后稽核. 默认 true */
        @Builder.Default
        boolean enablePostAudit = true;

        // ========== 后操作过滤 ==========

        /** 后操作类型过滤: null 或空=全部, "SUB"=仅 SUB 等 */
        String postOpsFilter;

        // ========== Detail 状态更新 ==========

        /** 非 null 时在管线结束时更新明细表状态(分桶场景) */
        Detail detail;

        /** 处理节点(用于 updateDetailStatus) */
        String nodeId;
    }
}
