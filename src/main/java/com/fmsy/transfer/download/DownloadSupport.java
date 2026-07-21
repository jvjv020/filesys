package com.fmsy.transfer.download;

import com.fmsy.audit.AuditScenario;
import com.fmsy.audit.AuditService;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.ftp.FtpClient;
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
 * 下载场景 Support — 跨 3 个 Download Handler 共享的下载协议纯方法集合。
 *
 * <p>每个方法只做一件事，Handler 负责组合编排各阶段：<br>
 * preAudit/count → preCheckAndMkdirs → checkOverwriteAllowed → generateFile → postProcess → postAudit
 *
 * <p>纯方法列表：
 * <ul>
 *   <li>{@link #preAudit} — 整表前稽核</li>
 *   <li>{@link #preAuditByBucket} — 分桶前稽核</li>
 *   <li>{@link #countRecords} — 整表计数</li>
 *   <li>{@link #preCheckAndMkdirs} — 前置标志检查 + 目录创建</li>
 *   <li>{@link #checkOverwriteAllowed} — 覆盖检查</li>
 *   <li>{@link #generateFile} — 生成数据文件（整表或分桶）</li>
 *   <li>{@link #postProcess} — FTP 后操作（支持按类型过滤）</li>
 *   <li>{@link #postAudit} — 后稽核</li>
 *   <li>{@link #updateDetailStatus} — 更新明细表状态</li>
 *   <li>{@link #rollbackAfterPostAuditFailure} — 后审计失败回滚</li>
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

    /**
     * 整表计数:SELECT COUNT(*) FROM table。
     */
    public int countRecords(TransferConfig config) {
        return targetTableRepository.count(config.getDbName(), config.getTableName());
    }

    /**
     * 分桶计数:SELECT COUNT(*) FROM table WHERE splitField = fieldValue。
     */
    public int countByBucket(TransferConfig config, String splitFields, String fieldValue) {
        return targetTableRepository.countByBucket(
                config.getDbName(), config.getTableName(), splitFields, fieldValue);
    }

    // ==================== 前操作 ====================

    /**
     * 前操作 — READY/FLAG 检查 + 目录创建，返回 false 表示检查未通过。
     */
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

    // ==================== 覆盖检查 ====================

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

    // ==================== 生成文件 ====================

    /**
     * 生成文件 — 整表或分桶模式，返回写入记录数。
     *
     * @param client      FTP 客户端
     * @param config      传输配置
     * @param wholeTable  true=ParallelFileGenerator(整表), false=streamBucketData(分桶)
     * @param fieldValue  分桶字段值(wholeTable=false 时使用)
     * @param mapping     预构建的字段映射（可为 null，为 null 时自动构建）
     * @param recordCount 预期写入记录数
     * @param filePath    目标文件路径
     * @return 写入的记录数
     */
    public int generateFile(FtpClient client, TransferConfig config, boolean wholeTable,
                             String fieldValue, FieldMapping mapping, int recordCount,
                             String filePath) throws Exception {
        FileConverter converter = converterFactory.get(config.getParserType());
        // 优先使用调用方预构建的 FieldMapping（多桶场景复用，避免重复查表元数据）
        FieldMapping effectiveMapping = mapping != null
                ? mapping
                : fieldMappingBuilder.buildForDownload(config);

        try (OutputStream os = client.getOutputStream(filePath)) {
            if (wholeTable) {
                return parallelFileGenerator.generate(os, config, converter, effectiveMapping, recordCount);
            }
            try (var data = targetTableRepository.streamBucketData(
                    config.getDbName(), config.getTableName(),
                    config.getSplitFields(), fieldValue)) {
                converter.generate(os, data, effectiveMapping);
            }
            return recordCount;
        } finally {
            client.completePendingCommand();
        }
    }

    // ==================== 后操作 ====================

    /**
     * FTP 后操作 — 写入 SUB/FB/TOTAL 等标志文件。
     *
     * @param client        已借出的 FTP 客户端
     * @param config        传输配置
     * @param targetFile    目标文件路径信息
     * @param count         写入的记录数（作为 {@code {C}} 变量传入后操作）
     * @param postOpsFilter 后操作类型过滤: null 或空=全部, "SUB"=仅 SUB 等
     */
    public void postProcess(FtpClient client, TransferConfig config,
                            ResolvedPath targetFile, int count, String postOpsFilter) {
        String postOps = config.getPostOperations();
        if (postOps == null || postOps.isEmpty()) {
            return;
        }
        String effectiveOps = postOpsFilter != null && !postOpsFilter.isEmpty()
                ? FlagFileService.filterOpsByType(postOps, postOpsFilter)
                : postOps;
        if (effectiveOps != null && !effectiveOps.isEmpty()) {
            transferSupport.postProcess(client, effectiveOps, targetFile, Map.of("C", String.valueOf(count)));
        }
    }

    // ==================== 明细表状态更新 ====================

    /**
     * 更新明细表状态。
     *
     * @param detailId 明细 ID（为 null 时跳过）
     * @param status   目标状态
     * @param nodeId   处理节点
     */
    public void updateDetailStatus(Long detailId, String status, String nodeId) {
        if (detailId == null) {
            return;
        }
        try {
            detailRepository.updateStatus(detailId, status, nodeId);
        } catch (Exception e) {
            log.error("Failed to update detail status for id={}, status={}: {}",
                    detailId, status, e.getMessage());
        }
    }

    // ==================== 回滚 ====================

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
}