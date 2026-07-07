package com.fmsy.transfer.download;

import com.fmsy.audit.AuditScenario;
import com.fmsy.audit.AuditService;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.Detail;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.BooleanUtils;
import com.fmsy.util.ColumnNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 下载场景 Support — 跨 3 个 Download Handler 共享的下载协议方法 + 工具。
 *
 * <p>4 个公共方法(resolveFilePath / preCheck / postProcess / handleEmptyData)已抽出到
 * {@link com.fmsy.transfer.TransferSupport},Handler 直接注入使用。
 * 本类只保留方向特有的:
 * <ul>
 *   <li>预审计:DB 记录数 vs auditCount(Download 语义:DB→FTP 方向,先看 DB)</li>
 *   <li>分桶预审计 / 后审计:文件行数 vs DB 记录数</li>
 *   <li>工具:updateDetailStatusForBucket / checkOverwriteAllowed /
 *       extractSubfileName / buildSplitFieldPredicates</li>
 *   <li>跨场景状态判定:determineMainStatus + BucketSummary</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadSupport {

    private final AuditService auditService;
    private final TargetTableRepository targetTableRepository;
    private final DetailRepository detailRepository;

    // ==================== 协议方法(方向特有) ====================

    /**
     * 预审计:DB 记录数与 auditCount 比较(DOWNLOAD 模式)
     *
     * @return 实际 DB 记录数（审计通过时）；-1（审计不通过）
     */
    public int preAudit(TransferConfig config, int auditCount) {
        return auditService.preAudit(AuditScenario.DOWNLOAD, config.getTableName(), auditCount,
                config.getDbName());
    }

    /**
     * 分桶预审计:DB 桶范围 COUNT 与 auditCount 比较
     *
     * @return 实际桶记录数（审计通过时）；-1（审计不通过）
     */
    public int preAuditByBucket(TransferConfig config, int auditCount, String fieldValue) {
        return auditService.preAuditByBucket(config.getTableName(),
                config.getSplitFields(), fieldValue, auditCount, config.getDbName());
    }

    /**
     * 后审计:文件行数与 DB 记录数比较(DOWNLOAD 模式)
     * <p>内部会重新 COUNT 全表。
     */
    public boolean postAudit(TransferConfig config, String filePath) {
        return auditService.postAudit(AuditScenario.DOWNLOAD, config.getFtpName(),
                config.getTableName(), filePath, -1, config.getDbName());
    }

    /**
     * 后审计:文件行数与已知 DB 记录数比较，避免内部重复 COUNT。
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
     * 更新明细表状态(SUCCESS/SKIPPED/ERROR)。
     * 仅当桶来自 BATCH 模式(明细表有 id 记录)时执行,
     * SERIAL 模式桶是 transient 对象,无 id,跳过更新。
     */
    public void updateDetailStatusForBucket(Detail bucket, String status, String nodeId) {
        if (bucket == null) return;
        Long detailId = bucket.getId();
        if (detailId == null) return;
        try {
            detailRepository.updateStatus(detailId, status, nodeId);
        } catch (Exception e) {
            log.error("Failed to update detail status for bucket id={}, status={}: {}",
                    detailId, status, e.getMessage());
        }
    }

    /**
     * 覆盖检查(迭代 #8):DOWNLOAD_SINGLE 目标文件已存在且配置不允许覆盖时,
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
     * 从文件路径中提取子文件名(不含扩展名)
     */
    public String extractSubfileName(String filePath) {
        if (filePath == null) return null;
        int lastSlash = filePath.lastIndexOf('/');
        String name = lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 把 {@code "col1,col2"} 形式的 splitFields 与 {@code ["v1","v2"]} 形式的 fieldValues
     * 一一对应转成谓词列表。
     */
    public List<String> buildSplitFieldPredicates(String splitFields, List<String> fieldValues,
                                                  List<Object> outParams) {
        List<String> predicates = new ArrayList<>();
        if (splitFields == null || splitFields.isEmpty() || fieldValues == null || fieldValues.isEmpty()) {
            return predicates;
        }
        String[] fieldNames = splitFields.split(",");
        if (fieldNames.length != fieldValues.size()) {
            log.warn("buildSplitFieldPredicates: splitFields 与 fieldValues 长度不一致,fields={}, values={},回退到取前 min 个",
                    Arrays.toString(fieldNames), fieldValues);
        }
        int n = Math.min(fieldNames.length, fieldValues.size());
        for (int i = 0; i < n; i++) {
            String name = fieldNames[i].trim();
            if (name.isEmpty()) {
                log.warn("buildSplitFieldPredicates: splitFields 第 {} 列为空,已跳过", i);
                continue;
            }
            predicates.add(name + " = ?");
            outParams.add(fieldValues.get(i));
        }
        return predicates;
    }

    // ==================== 跨场景主状态判定 ====================

    /**
     * DOWNLOAD_SINGLE_NODE 返回值 — 桶级汇总。
     */
    public record BucketSummary(int totalRecords, boolean allFilesSuccess,
                                 int failedCount, int skippedCount) {
    }

    /**
     * P0 #1:根据桶/文件汇总结果判定主指令最终状态(SingleNode 用)。
     */
    public static String determineMainStatus(boolean allSuccess, int failedCount, int skippedCount) {
        return TransferSupport.determineMainStatus(allSuccess, failedCount, skippedCount);
    }

    /**
     * BucketSummary 重载 — 桶级汇总转主状态。
     */
    public static String determineMainStatus(BucketSummary summary) {
        return determineMainStatus(summary.allFilesSuccess(), summary.failedCount(), summary.skippedCount());
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
}
