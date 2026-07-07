package com.fmsy.transfer.upload;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.SystemConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 上传场景 Support — 跨 3 个 Upload Handler 共享的上传协议方法。
 *
 * <p>4 个公共方法(resolveFilePath / preCheck / postProcess / handleEmptyData)已抽出到
 * {@link com.fmsy.transfer.TransferSupport},Handler 直接注入使用。
 * 本类只保留方向特有的:
 * <ul>
 *   <li>预审计:文件记录数 vs auditCount,使用 {@link FileConverter#countRecords} 按格式统计</li>
 *   <li>后审计:文件记录数 vs 插入记录数</li>
 *   <li>核心流式插入循环:{@link #insertBatchInTx}</li>
 *   <li>跨场景状态判定:determineMainStatus + UploadResult 记录</li>
 * </ul>
 *
 * <p>所有场景统一走 {@link #insertBatchInTx} 单事务插入,
 * 清表与增量模式都适用,失败时全量回滚。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadSupport {

    private final FtpPool ftpPool;
    private final TargetTableRepository targetTableRepository;
    private final DataSourceConfig.DbPool dbPool;
    private final TransferSupport transferSupport;

    // ==================== 协议方法(方向特有) ====================

    /**
     * 预审计:文件记录数 vs auditCount(Upload 语义)
     *
     * <p>使用格式对应的 {@link FileConverter#countRecords} 统计文件记录数,
     * 而非通用行数统计(CSV/TXT 逐行、DBF 从文件头读、XML 返回 -1)。
     *
     * <p>无论审计是否配置,都会统计文件记录数并返回,
     * 供 {@link #postAudit} 复用,避免重复读文件。
     *
     * @param auditCount 期望行数(null 或负数表示不校验)
     * @param config     传输配置
     * @param filePath   FTP 文件路径
     * @param converter  文件格式转换器(用于按格式统计记录数)
     * @return 文件的记录数(审计通过时);审计不通过或无法统计返回 -1
     */
    public int preAudit(Integer auditCount, TransferConfig config, String filePath, FileConverter converter) {
        try {
            int recordCount = ftpPool.withClient(config.getFtpName(), client -> {
                try (InputStream is = client.getInputStream(filePath)) {
                    return converter.countRecords(is, null);
                }
            });
            if (recordCount < 0) {
                log.error("Pre-audit: unable to count records for {} format, skipping", converter.getFormat());
                return -1;
            }
            if (auditCount != null && auditCount >= 0) {
                boolean passed = recordCount == auditCount;
                log.info("Pre-audit file records: expected={}, actual={}, passed={}", auditCount, recordCount, passed);
                return passed ? recordCount : -1;
            }
            return recordCount;
        } catch (Exception e) {
            log.error("Pre-audit failed: {}", e.getMessage(), e);
            return -1;
        }
    }

    /**
     * 后审计:本次插入记录数 vs 文件记录数(Upload 语义)
     *
     * <p>比对的是"文件记录数"与"成功插入的记录数",而非全表 COUNT。
     * 多文件场景下,全表 COUNT 包含其他文件的数据,对比无意义。
     *
     * @param config        传输配置
     * @param fileLineCount 该文件的记录行数(由 preAudit 返回)
     * @param insertedCount 本次成功插入的记录数
     * @return true=审计通过
     */
    public boolean postAudit(TransferConfig config, int fileLineCount, int insertedCount) {
        boolean passed = fileLineCount == insertedCount;
        log.info("Post-audit upload: fileRecords={}, inserted={}, passed={}",
                fileLineCount, insertedCount, passed);
        return passed;
    }

    // ==================== 核心流式插入(单事务) ====================

    /**
     * 在事务中执行插入 — 每个文件的全部数据在一个事务内。
     *
     * <p>清表与增量模式均适用:失败时全部回滚,不影响其他文件。</p>
     *
     * <p>字段顺序取自 {@link FieldMapping#getTableFields()} (已排除 ignoreFields 和 detail 字段),
     * 值通过 {@link FieldMapping#getValue} 获取,extraFields 中的固定值会被自动补入。</p>
     *
     * <p>适用于:
     * <ul>
     *   <li>{@link MultiDirectoryUploadHandler.FileTask} — 多目录并发的每个文件</li>
     *   <li>{@link MultiBatchUploadHandler} — 明细表的每个文件</li>
     *   <li>{@link SingleUploadHandler} — 单文件(清表/增量模式)</li>
     * </ul>
     */
    public int insertBatchInTx(TransferConfig config, Iterator<List<Map<String, Object>>> dataIter,
                               FieldMapping mapping) {
        String dbName = config.getDbName();
        TransactionTemplate tx = dbPool.getTransactionTemplate(dbName);
        EmptyDataHandling emptyHandling = config.getEmptyDataHandling();
        List<String> fields = mapping != null ? mapping.getTableFields() : null;

        return tx.execute(status -> {
            int batchSize = SystemConstants.DEFAULT_BATCH_SIZE;
            List<Object[]> batch = new ArrayList<>();
            int totalRecords = 0;

            while (dataIter.hasNext()) {
                List<Map<String, Object>> batchChunk = dataIter.next();
                if (batchChunk.isEmpty()) continue;

                if (fields == null) {
                    fields = extractFields(batchChunk.get(0));
                }

                for (Map<String, Object> record : batchChunk) {
                    Object[] values = new Object[fields.size()];
                    for (int i = 0; i < fields.size(); i++) {
                        values[i] = mapping != null
                                ? mapping.getValue(record, fields.get(i))
                                : record.get(fields.get(i));
                    }
                    batch.add(values);

                    if (batch.size() >= batchSize) {
                        targetTableRepository.batchInsert(
                                config.getDbName(), config.getTableName(), fields, batch);
                        totalRecords += batch.size();
                        batch.clear();
                    }
                }
            }

            if (!batch.isEmpty()) {
                targetTableRepository.batchInsert(
                        config.getDbName(), config.getTableName(), fields, batch);
                totalRecords += batch.size();
            }

            if (!transferSupport.handleEmptyData(totalRecords, emptyHandling)) {
                throw new RuntimeException("Empty data handling: " + emptyHandling);
            }

            log.debug("Inserted {} records in single transaction", totalRecords);
            return totalRecords;
        });
    }

    /** 获取字段名列表 */
    private static List<String> extractFields(Map<String, Object> record) {
        return record.keySet().stream().toList();
    }

    // ==================== 跨场景主状态判定 ====================

    public record UploadResult(int records, int successCount, int skippedCount, int failedCount) {
        public static UploadResult allSkipped() { return new UploadResult(0, 0, 1, 0); }
    }

    public static String determineMainStatus(UploadResult result) {
        boolean allSuccess = result.failedCount() == 0 && result.skippedCount() == 0;
        return TransferSupport.determineMainStatus(allSuccess, result.failedCount(), result.skippedCount());
    }

}
