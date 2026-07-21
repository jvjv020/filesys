package com.fmsy.transfer.download;

import com.fmsy.audit.AuditScenario;
import com.fmsy.audit.AuditService;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.ftp.FtpClient;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.Map;

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
 * <p>管线通过两个 public 方法暴露:
 * <ul>
 *   <li>{@link #executeWholeTablePipeline} — 整表模式,含前稽核/后稽核/覆盖检查</li>
 *   <li>{@link #executeBucketPipeline} — 分桶模式,支持明细状态更新和后操作过滤</li>
 * </ul>
 *
 * <p>各 Handler 按场景调用:
 * <ul>
 *   <li>{@link SingleDownloadHandler} — 整表模式</li>
 *   <li>{@link SingleNodeDownloadHandler} — 分桶模式,跳过前操作/后稽核(聚合层做)</li>
 * </ul>
 *
 * <p>FTP 连接生命周期由 {@link TransferSupport#executeWithClient} 模板统一管理，
 * 内部阶段方法不直接借还连接（与 {@link com.fmsy.transfer.upload.UploadSupport} 风格一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadSupport {

    private final AuditService auditService;
    private final TargetTableRepository targetTableRepository;
    private final DetailRepository detailRepository;
    private final TransferSupport transferSupport;
    private final ConverterFactory converterFactory;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final ParallelFileGenerator parallelFileGenerator;

    // ==================== 单文件下载管线(public 入口) ====================

    /**
     * 整表模式管线:全表数据 → 单个 FTP 文件。
     *
     * <p>包含前稽核、前操作、覆盖检查、文件生成、后操作、后稽核等全部阶段。
     *
     * @param ftpName            FTP 服务器名称
     * @param config             传输配置
     * @param targetFile         预解析的目标文件路径
     * @param expectedAuditCount 预期稽核数(null 则跳过前稽核仅计数)
     * @param fieldMapping       预构建的字段映射(null 则管线内部自动构建)
     * @param enableOverwriteCheck 是否启用覆盖检查
     * @param enablePostAudit    是否启用后稽核
     * @param detail             明细对象(非 null 时更新明细表状态)
     * @param nodeId             处理节点(用于 updateDetailStatus)
     * @return 管线执行结果
     */
    public PipelineResult executeWholeTablePipeline(String ftpName, TransferConfig config,
                                                     ResolvedPath targetFile,
                                                     Integer expectedAuditCount,
                                                     FieldMapping fieldMapping,
                                                     boolean enableOverwriteCheck,
                                                     boolean enablePostAudit,
                                                     Detail detail,
                                                     String nodeId) {
        return doExecutePipeline(ftpName, config, targetFile,
                true, null, expectedAuditCount, fieldMapping,
                true, enableOverwriteCheck, enablePostAudit,
                null, detail, nodeId);
    }

    /**
     * 分桶模式管线:桶范围数据 → 单个 FTP 文件。
     *
     * <p>支持明细状态更新和后操作类型过滤，覆盖检查默认关闭(由整表层处理)。
     *
     * @param ftpName            FTP 服务器名称
     * @param config             传输配置
     * @param targetFile         预解析的目标文件路径
     * @param fieldValue         分桶字段值
     * @param expectedAuditCount 预期稽核数(null 则跳过前稽核仅计数)
     * @param fieldMapping       预构建的字段映射(null 则管线内部自动构建)
     * @param enablePreCheck     是否启用前操作(READY/FLAG 检查 + 目录创建)
     * @param enablePostAudit    是否启用后稽核
     * @param postOpsFilter      后操作类型过滤(null 或空=全部)
     * @param detail             明细对象(非 null 时更新明细表状态)
     * @param nodeId             处理节点(用于 updateDetailStatus)
     * @return 管线执行结果
     */
    public PipelineResult executeBucketPipeline(String ftpName, TransferConfig config,
                                                  ResolvedPath targetFile,
                                                  String fieldValue,
                                                  Integer expectedAuditCount,
                                                  FieldMapping fieldMapping,
                                                  boolean enablePreCheck,
                                                  boolean enablePostAudit,
                                                  String postOpsFilter,
                                                  Detail detail,
                                                  String nodeId) {
        return doExecutePipeline(ftpName, config, targetFile,
                false, fieldValue, expectedAuditCount, fieldMapping,
                enablePreCheck, false, enablePostAudit,
                postOpsFilter, detail, nodeId);
    }

    // ==================== 统一管线实现 ====================

    /**
     * 单文件下载管线: 前稽核 → 空数据处理 → (FTP 会话: 前操作 → 生成文件 → 后操作) → 后稽核。
     *
     * <p>两个 public 入口({@link #executeWholeTablePipeline} / {@link #executeBucketPipeline})
     * 委派到此方法,以不同默认参数执行。FTP 客户端生命周期由管线内部管理。
     */
    private PipelineResult doExecutePipeline(String ftpName, TransferConfig config,
                                              ResolvedPath targetFile,
                                              boolean wholeTable, String fieldValue,
                                              Integer expectedAuditCount, FieldMapping fieldMapping,
                                              boolean enablePreCheck, boolean enableOverwriteCheck,
                                              boolean enablePostAudit,
                                              String postOpsFilter, Detail detail, String nodeId) {
        String filePath = targetFile != null ? targetFile.fullPath() : null;
        log.debug("Pipeline start: ftp={}, path={}, wholeTable={}, fieldValue={}",
                ftpName, filePath, wholeTable, fieldValue);

        /* ---------- Phase 1: 前稽核 / 计数 (DB-only) ---------- */
        int recordCount = resolveRecordCount(config, wholeTable, fieldValue, expectedAuditCount);
        if (recordCount < 0) {
            log.warn("Pre-audit failed for {}, auditCount={}", filePath, expectedAuditCount);
            updateDetailStatus(detail, nodeId, ColumnNames.STATUS_SKIPPED);
            return new PipelineResult(0, false, ColumnNames.STATUS_SKIPPED, filePath);
        }

        /* ---------- Phase 2: 空数据处理 ---------- */
        PipelineResult emptyResult = handleEmptyDataIfNeeded(config, recordCount, filePath, detail, nodeId);
        if (emptyResult != null) return emptyResult;

        /* ---------- Phase 3-5: FTP 传输 + 后稽核 + 状态更新 ---------- */
        try {
            return transferSupport.executeWithClient(ftpName, client ->
                    executeFtpPhases(client, config, targetFile,
                            wholeTable, fieldValue, fieldMapping,
                            enablePreCheck, enableOverwriteCheck, enablePostAudit,
                            postOpsFilter, detail, nodeId,
                            recordCount, filePath));
        } catch (Exception e) {
            log.error("Pipeline failed for {}: {}", filePath, e.getMessage(), e);
            updateDetailStatus(detail, nodeId, ColumnNames.STATUS_ERROR);
            return new PipelineResult(0, false, ColumnNames.STATUS_ERROR, filePath);
        }
    }

    // ==================== Phase 方法 ====================

    /**
     * Phase 1: 前稽核或计数 — 返回实际记录数；-1 表示稽核不通过。
     */
    private int resolveRecordCount(TransferConfig config, boolean wholeTable,
                                    String fieldValue, Integer expectedAuditCount) {
        if (expectedAuditCount != null && expectedAuditCount >= 0) {
            int count = wholeTable
                    ? preAudit(config, expectedAuditCount)
                    : preAuditByBucket(config, expectedAuditCount, fieldValue);
            return count < 0 ? -1 : count;
        }
        return wholeTable
                ? countRecords(config)
                : targetTableRepository.countByBucket(
                        config.getDbName(), config.getTableName(),
                        config.getSplitFields(), fieldValue);
    }

    /**
     * Phase 2: 空数据处理 — 记录数为 0 且配置不允许空数据时返回错误/跳过结果。
     */
    private PipelineResult handleEmptyDataIfNeeded(TransferConfig config, int recordCount,
                                                    String filePath, Detail detail, String nodeId) {
        if (recordCount > 0) return null;
        if (transferSupport.handleEmptyData(0, config.getEmptyDataHandling())) return null;

        String status = config.getEmptyDataHandling() == EmptyDataHandling.ERROR
                ? ColumnNames.STATUS_ERROR : ColumnNames.STATUS_SKIPPED;
        log.warn("Empty data handling ({}) for {}", config.getEmptyDataHandling(), filePath);
        updateDetailStatus(detail, nodeId, status);
        return new PipelineResult(0, false, status, filePath);
    }

    /**
     * Phase 3-5: 在已有 FTP 会话中执行前操作 → 生成文件 → 后操作 → 后稽核 → 状态更新。
     *
     * <p>由 {@link #doExecutePipeline} 在 {@code executeWithClient} 内部调用，
     * FTP 连接生命周期由调用方管理，本方法不负责借还。
     */
    private PipelineResult executeFtpPhases(FtpClient client, TransferConfig config,
                                             ResolvedPath targetFile,
                                             boolean wholeTable, String fieldValue,
                                             FieldMapping fieldMapping,
                                             boolean enablePreCheck, boolean enableOverwriteCheck,
                                             boolean enablePostAudit,
                                             String postOpsFilter, Detail detail, String nodeId,
                                             int recordCount, String filePath) {
        try {
            /* ---------- Phase 3a: 前操作 ---------- */
            if (enablePreCheck) {
                PipelineResult checkResult = preCheckPath(client, config, targetFile, filePath, detail, nodeId);
                if (checkResult != null) return checkResult;
            }

            /* ---------- Phase 3b: 覆盖检查(可选) ---------- */
            if (enableOverwriteCheck
                    && !checkOverwriteAllowed(client, filePath, config.getOverwriteFlag())) {
                log.warn("Overwrite denied for {}", filePath);
                updateDetailStatus(detail, nodeId, ColumnNames.STATUS_ERROR);
                return new PipelineResult(0, false, ColumnNames.STATUS_ERROR, filePath);
            }

            /* ---------- Phase 3c: 生成文件 ---------- */
            int count = generateFile(client, config, wholeTable, fieldValue, fieldMapping, recordCount, filePath);

            /* ---------- Phase 3d: 后操作 ---------- */
            doPostProcess(client, config, postOpsFilter, targetFile, count);

            /* ---------- Phase 4: 后稽核 ---------- */
            if (enablePostAudit) {
                PipelineResult auditResult = checkPostAudit(config, client, filePath, count, detail, nodeId);
                if (auditResult != null) return auditResult;
            }

            /* ---------- Phase 5: 更新 Detail 状态 ---------- */
            updateDetailStatus(detail, nodeId, ColumnNames.STATUS_SUCCESS);

            log.info("Pipeline completed: {}, records={}", filePath, count);
            return new PipelineResult(count, true, ColumnNames.STATUS_SUCCESS, filePath);

        } catch (Exception e) {
            log.error("Pipeline failed for {}: {}", filePath, e.getMessage(), e);
            updateDetailStatus(detail, nodeId, ColumnNames.STATUS_ERROR);
            return new PipelineResult(0, false, ColumnNames.STATUS_ERROR, filePath);
        }
    }

    /** 前操作 — READY/FLAG 检查 + 目录创建，返回 false 表示检查未通过。 */
    public boolean preCheckAndMkdirs(FtpClient client, TransferConfig config,
                                      ResolvedPath targetFile, String filePath) {
        if (!transferSupport.preCheck(client, config, targetFile)) {
            return false;
        }
        String parentDir = FilePathUtils.extractParentDirectory(filePath);
        if (parentDir != null && !parentDir.isEmpty()) {
            client.mkdirs(parentDir);
        }
        return true;
    }

    /** 前操作 — READY/FLAG 检查 + 目录创建，返回 PipelineResult 兼容管线流。 */
    private PipelineResult preCheckPath(FtpClient client, TransferConfig config,
                                         ResolvedPath targetFile,
                                         String filePath, Detail detail, String nodeId) {
        if (!preCheckAndMkdirs(client, config, targetFile, filePath)) {
            log.warn("Pre-check failed for {}", filePath);
            updateDetailStatus(detail, nodeId, ColumnNames.STATUS_SKIPPED);
            return new PipelineResult(0, false, ColumnNames.STATUS_SKIPPED, filePath);
        }
        return null;
    }

    /** 生成文件 — 整表或分桶模式，返回写入记录数。 */
    private int generateFile(FtpClient client, TransferConfig config,
                              boolean wholeTable, String fieldValue,
                              FieldMapping fieldMapping,
                              int recordCount, String filePath) throws Exception {
        FileConverter converter = converterFactory.get(config.getParserType());
        // 优先使用调用方预构建的 FieldMapping（多桶场景复用，避免重复查表元数据）
        FieldMapping mapping = fieldMapping != null
                ? fieldMapping
                : fieldMappingBuilder.buildForDownload(config);

        try (OutputStream os = client.getOutputStream(filePath)) {
            if (wholeTable) {
                return parallelFileGenerator.generate(os, config, converter, mapping, recordCount);
            }
            try (var data = targetTableRepository.streamBucketData(
                    config.getDbName(), config.getTableName(),
                    config.getSplitFields(), fieldValue)) {
                converter.generate(os, data, mapping);
            }
            return recordCount;
        } finally {
            client.completePendingCommand();
        }
    }

    /** 后操作 — 写入 SUB/FB/TOTAL 等标志文件。 */
    private void doPostProcess(FtpClient client, TransferConfig config,
                                String postOpsFilter, ResolvedPath targetFile,
                                int count) {
        String postOps = resolvePostOps(config, postOpsFilter);
        if (postOps != null && !postOps.isEmpty()) {
            transferSupport.postProcess(client, postOps, targetFile, Map.of("C", String.valueOf(count)));
        }
    }

    /** 后稽核 — 文件行数与 DB 记录数比对，失败时直接回滚 FTP 文件。 */
    private PipelineResult checkPostAudit(TransferConfig config,
                                           FtpClient client, String filePath, int count,
                                           Detail detail, String nodeId) {
        if (!postAudit(config, filePath, count)) {
            log.error("Post-audit failed for {}, rolling back file", filePath);
            rollbackAfterPostAuditFailure(client, filePath, "pipeline post-audit");
            updateDetailStatus(detail, nodeId, ColumnNames.STATUS_ERROR);
            return new PipelineResult(count, false, ColumnNames.STATUS_ERROR, filePath);
        }
        return null;
    }

    /** 解析后操作字符串:按 postOpsFilter 过滤 config 的后操作列表。 */
    private String resolvePostOps(TransferConfig config, String postOpsFilter) {
        String postOps = config.getPostOperations();
        if (postOps == null || postOps.isEmpty()) {
            return null;
        }
        if (postOpsFilter != null && !postOpsFilter.isEmpty()) {
            return com.fmsy.fileops.FlagFileService.filterOpsByType(postOps, postOpsFilter);
        }
        return postOps;
    }

    /** 更新明细表状态,仅在 detail 有 id 时执行。 */
    private void updateDetailStatus(Detail detail, String nodeId, String status) {
        if (detail == null || detail.getId() == null) {
            return;
        }
        try {
            detailRepository.updateStatus(detail.getId(), status, nodeId);
        } catch (Exception e) {
            log.error("Failed to update detail status for id={}, status={}: {}",
                    detail.getId(), status, e.getMessage());
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

    // ==================== 值类型(管线结果) ====================

    /**
     * 管线执行结果 — Java 16 record，与上传 {@link com.fmsy.transfer.upload.UploadSupport.UploadResult} 风格一致。
     *
     * @param recordCount 写入/处理的记录数
     * @param success     是否成功
     * @param status      状态码（SUCCESS / SKIPPED / ERROR）
     * @param filePath    目标文件路径（用于错误描述和回滚）
     */
    public record PipelineResult(int recordCount, boolean success, String status, String filePath) {
    }
}
