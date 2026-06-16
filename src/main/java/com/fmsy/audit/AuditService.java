package com.fmsy.audit;

import com.fmsy.ftp.FtpPool;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.util.ColumnNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 审计服务 - 合并PreAudit和PostAudit
 *
 * <p>P1 #6 优化:preAudit 返回实际计数而非 boolean,避免调用方二次 COUNT。
 * postAudit 新增已知 DB 记录数重载,避免内部再查 DB。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final FtpPool ftpPool;
    private final TargetTableRepository targetTableRepository;

    /**
     * 执行预审计 — 返回实际记录数，-1 表示审计不通过。
     *
     * @param scenario  场景类型
     * @param source    数据源（上传为FTP信息，下载为表名）
     * @param auditCount 期望的记录数
     * @return 实际记录数（通过时）；-1（不通过或异常）
     */
    public int preAudit(AuditScenario scenario, Object source, int auditCount) {
        if (auditCount < 0) {
            return auditCount; // 负数表示不审计，返回原值
        }
        try {
            return switch (scenario) {
                case UPLOAD -> preAuditFileRecords(source, auditCount);
                case DOWNLOAD -> preAuditDbRecords(source, auditCount);
            };
        } catch (Exception e) {
            log.error("Pre-audit failed: {}", e.getMessage(), e);
            return -1;
        }
    }

    /**
     * 分桶场景预审计 — 返回实际记录数，-1 表示审计不通过。
     */
    public int preAuditByBucket(String tableName, String splitField, String fieldValue, int auditCount) {
        if (auditCount < 0) {
            return auditCount;
        }
        try {
            if (splitField == null || splitField.isEmpty()
                    || fieldValue == null || fieldValue.isEmpty()) {
                log.warn("preAuditByBucket missing splitField/fieldValue, falling back to whole-table count");
                return preAudit(AuditScenario.DOWNLOAD, tableName, auditCount);
            }
            int count = targetTableRepository.countByBucket(
                    ColumnNames.DEFAULT_DB, tableName, splitField, fieldValue);
            boolean passed = count == auditCount;
            log.info("Pre-audit by bucket: splitField={}, value={}, expected={}, actual={}, passed={}",
                    splitField, fieldValue, auditCount, count, passed);
            return passed ? count : -1;
        } catch (Exception e) {
            log.error("Pre-audit by bucket failed: {}", e.getMessage(), e);
            return -1;
        }
    }

    /**
     * 执行后审计（不含已知 DB 记录数）。
     */
    public boolean postAudit(AuditScenario scenario, String ftpName, Object source, Object target) {
        return postAudit(scenario, ftpName, source, target, -1);
    }

    /**
     * 执行后审计 — 传入已知 DB 记录数，避免内部重复 COUNT。
     *
     * @param scenario    场景类型
     * @param ftpName     FTP配置名
     * @param source      数据源（表名）
     * @param target      目标（文件路径）
     * @param knownDbCount 已知的 DB 记录数，&lt;0 时内部重新 COUNT
     * @return true=审计通过
     */
    public boolean postAudit(AuditScenario scenario, String ftpName, Object source, Object target,
                             int knownDbCount) {
        try {
            return switch (scenario) {
                case UPLOAD -> postAuditUpload(ftpName, source, target);
                case DOWNLOAD -> postAuditDownload(ftpName, source, target, knownDbCount);
            };
        } catch (Exception e) {
            log.error("Post-audit failed: {}", e.getMessage(), e);
            return false;
        }
    }

    // ==================== 预审计实现 ====================

    private int preAuditFileRecords(Object source, int auditCount) throws Exception {
        log.info("Pre-audit file records, expected: {}", auditCount);

        if (source instanceof String[] args && args.length >= 2) {
            String ftpName = args[0];
            String filePath = args[1];
            int lineCount = ftpPool.withClient(ftpName, client -> client.countFileLines(filePath));
            boolean passed = lineCount == auditCount;
            log.info("Pre-audit file records, expected: {}, actual: {}, passed={}", auditCount, lineCount, passed);
            return passed ? lineCount : -1;
        }
        return -1;
    }

    private int preAuditDbRecords(Object source, int auditCount) {
        String tableName = (String) source;
        int count = targetTableRepository.count(ColumnNames.DEFAULT_DB, tableName);
        boolean passed = count == auditCount;
        log.info("Pre-audit db records, expected: {}, actual: {}, passed={}", auditCount, count, passed);
        return passed ? count : -1;
    }

    // ==================== 后审计实现 ====================

    private boolean postAuditUpload(String ftpName, Object fileSource, Object dbTarget) throws Exception {
        int fileRecords = getFileRecordCount(ftpName, fileSource);
        String tableName = (String) dbTarget;
        int dbCount = targetTableRepository.count(ColumnNames.DEFAULT_DB, tableName);
        boolean passed = fileRecords == dbCount;
        log.info("Post-audit upload, file records: {}, db records: {}, passed={}", fileRecords, dbCount, passed);
        return passed;
    }

    private boolean postAuditDownload(String ftpName, Object dbSource, Object fileTarget,
                                      int knownDbCount) throws Exception {
        String tableName = (String) dbSource;
        int dbCount = knownDbCount >= 0 ? knownDbCount
                : targetTableRepository.count(ColumnNames.DEFAULT_DB, tableName);
        int fileRecords = getFileRecordCount(ftpName, fileTarget);
        boolean passed = dbCount == fileRecords;
        log.info("Post-audit download, db records: {}, file records: {}, passed={}", dbCount, fileRecords, passed);
        return passed;
    }

    private int getFileRecordCount(String ftpName, Object source) throws Exception {
        String resolvedFtpName;
        String filePath;
        if (source instanceof String[] args && args.length >= 2) {
            resolvedFtpName = args[0];
            filePath = args[1];
        } else if (source instanceof String s) {
            resolvedFtpName = ftpName;
            filePath = s;
        } else {
            return 0;
        }
        return ftpPool.withClient(resolvedFtpName, client -> client.countFileLines(filePath));
    }
}
