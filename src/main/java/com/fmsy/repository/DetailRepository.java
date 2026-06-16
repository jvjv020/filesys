package com.fmsy.repository;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.model.Detail;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.TableNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 明细表(明细表) Repository — 集中管理对该表的全部 SQL 访问。
 *
 * <p>主要调用方:
 * <ul>
 *   <li>{@code transfer/BucketDistributor} — competeBucket / countEmptyBuckets / createBuckets</li>
 *   <li>{@code transfer/upload/MultiBatchUploadHandler} — findUploadDetails</li>
 *   <li>{@code transfer/download/MultiNodeDownloadHandler} — updateAuditCount</li>
 *   <li>{@code polling/DetailPollingService} — findBucketData(动态表名,见 TargetTableRepository)</li>
 * </ul>
 */
@Slf4j
@Repository
public class DetailRepository {

    private final DataSourceConfig.DbPool dbPool;

    public DetailRepository(DataSourceConfig.DbPool dbPool) {
        this.dbPool = dbPool;
    }

    private JdbcTemplateWrapper getJdbc() {
        return dbPool.getJdbcTemplate(ColumnNames.DEFAULT_DB);
    }

    /** 字段列表(SELECT 与 Detail 行映射共用)— 包含桶分发的关键字段 */
    private static final String SELECT_FIELDS =
        ColumnNames.ID + ", " + ColumnNames.DETAIL_COMMAND_ID + ", " +
        ColumnNames.CATEGORY_CODE + ", " + ColumnNames.CONTROL_CODE + ", " +
        ColumnNames.FIELD_VALUE + ", " + ColumnNames.AUDIT_COUNT + ", " +
        ColumnNames.PROCESS_STATUS;

    private static final RowMapper<Detail> DETAIL_MAPPER = (rs, rowNum) -> {
        Detail detail = new Detail();
        detail.setId(rs.getLong(ColumnNames.ID));
        detail.setCommandId(rs.getLong(ColumnNames.DETAIL_COMMAND_ID));
        detail.setCategoryCode(rs.getString(ColumnNames.CATEGORY_CODE));
        detail.setControlCode(rs.getString(ColumnNames.CONTROL_CODE));
        detail.setFieldValue(rs.getString(ColumnNames.FIELD_VALUE));
        detail.setAuditCount(rs.getInt(ColumnNames.AUDIT_COUNT));
        detail.setStatus(rs.getString(ColumnNames.PROCESS_STATUS));
        return detail;
    };

    // ==================== 查询 ====================

    private static final String SQL_FIND_DETAILS_BY_COMMAND_ID =
        "SELECT " + SELECT_FIELDS +
        " FROM " + TableNames.DETAIL_TABLE +
        " WHERE " + ColumnNames.DETAIL_COMMAND_ID + "=? ORDER BY " + ColumnNames.ID;

    private static final String SQL_FIND_READY_BUCKETS =
        "SELECT " + SELECT_FIELDS +
        " FROM " + TableNames.DETAIL_TABLE +
        " WHERE " + ColumnNames.DETAIL_COMMAND_ID + "=? AND " +
        ColumnNames.PROCESS_STATUS + "=? ORDER BY " + ColumnNames.ID + " LIMIT ?";

    private static final String SQL_FIND_BUCKETS =
        "SELECT " + SELECT_FIELDS +
        " FROM " + TableNames.DETAIL_TABLE +
        " WHERE " + ColumnNames.DETAIL_COMMAND_ID + "=? AND " +
        ColumnNames.PROCESS_STATUS + "=? ORDER BY " + ColumnNames.ID + " LIMIT ?";

    private static final String SQL_FIND_UPLOAD_DETAILS =
        "SELECT * FROM " + TableNames.DETAIL_TABLE +
        " WHERE " + ColumnNames.DETAIL_COMMAND_ID + "=? AND " +
        ColumnNames.PROCESS_STATUS + "=?";

    /** 查询某命令的全部明细(主命令汇总场景) */
    public List<Detail> findByCommandId(Long commandId) {
        return getJdbc().query(SQL_FIND_DETAILS_BY_COMMAND_ID, DETAIL_MAPPER, commandId);
    }

    /**
     * 查询待处理桶(状态=空),带 limit。
     */
    public List<Detail> findReadyBuckets(Long commandId, int limit) {
        return getJdbc().query(SQL_FIND_READY_BUCKETS, DETAIL_MAPPER,
                commandId, ColumnNames.STATUS_EMPTY, limit);
    }

    /**
     * 查询某状态桶(行为同 BucketDistributor.getBuckets)。
     * @param commandId 主命令ID
     * @param status    桶状态(空 / 处理中 / 完成 等)
     * @param limit     返回上限
     */
    public List<Detail> findBucketsByStatus(Long commandId, String status, int limit) {
        return getJdbc().query(SQL_FIND_BUCKETS, DETAIL_MAPPER, commandId, status, limit);
    }

    /** 上传场景:按命令 ID 与指定状态查明细(由 MultiBatchUploadHandler 调用) */
    public List<Map<String, Object>> findUploadDetails(Long commandId, String status) {
        return getJdbc().queryForList(SQL_FIND_UPLOAD_DETAILS, commandId, status);
    }

    // ==================== 竞争 ====================

    private static final String SQL_COMPETE_BUCKET =
        "UPDATE " + TableNames.DETAIL_TABLE + " SET " +
        ColumnNames.PROCESSING_NODE + "=?, " + ColumnNames.PROCESS_STATUS + "=? " +
        "WHERE " + ColumnNames.ID + "=? AND " + ColumnNames.PROCESS_STATUS + "=?";

    private static final String SQL_COUNT_EMPTY_BUCKETS =
        "SELECT COUNT(*) FROM " + TableNames.DETAIL_TABLE +
        " WHERE " + ColumnNames.DETAIL_COMMAND_ID + "=? AND " +
        ColumnNames.PROCESS_STATUS + "=?";

    /**
     * 桶竞争 — 行为同 BucketDistributor.competeBucket。
     * @return 受影响行数(1=成功,0=已被其他节点抢走)
     */
    public int competeBucket(Long detailId, String nodeId) {
        return getJdbc().update(SQL_COMPETE_BUCKET, nodeId, ColumnNames.STATUS_PROCESSING,
                detailId, ColumnNames.STATUS_EMPTY);
    }

    /** 统计某主命令下空状态桶的数量(供 createChildCommands 使用) */
    public int countEmptyBuckets(Long commandId) {
        Integer count = getJdbc().queryForObject(SQL_COUNT_EMPTY_BUCKETS, Integer.class,
                commandId, ColumnNames.STATUS_EMPTY);
        return count != null ? count : 0;
    }

    // ==================== 状态更新 ====================

    private static final String SQL_UPDATE_STATUS =
        "UPDATE " + TableNames.DETAIL_TABLE + " SET " +
        ColumnNames.PROCESS_STATUS + "=?, " + ColumnNames.PROCESSING_NODE + "=? " +
        "WHERE " + ColumnNames.ID + "=?";

    private static final String SQL_UPDATE_AUDIT_COUNT =
        "UPDATE " + TableNames.DETAIL_TABLE + " SET " +
        ColumnNames.AUDIT_COUNT + "=? WHERE " + ColumnNames.ID + "=?";

    /** 更新单条明细状态(同时记录处理节点) */
    public void updateStatus(Long detailId, String status, String nodeId) {
        getJdbc().update(SQL_UPDATE_STATUS, status, nodeId, detailId);
        log.debug("Updated detail status: id={}, status={}, node={}", detailId, status, nodeId);
    }

    /** 更新单条明细的稽核数(DOWNLOAD_MULTI_NODE 预统计场景) */
    public void updateAuditCount(Long detailId, int auditCount) {
        getJdbc().update(SQL_UPDATE_AUDIT_COUNT, auditCount, detailId);
    }

    // ==================== 创建桶 ====================

    /**
     * 批量插入桶记录(行为同 BucketDistributor.createBuckets)。
     * 性能考虑:走单条多值 INSERT 一次写入,避免每桶一次往返。
     */
    public void createBuckets(Long commandId, List<String> bucketValues, String splitFields,
                              String categoryCode, String controlCode) {
        if (bucketValues == null || bucketValues.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO " + TableNames.DETAIL_TABLE + " (" +
                ColumnNames.DETAIL_COMMAND_ID + ", " + ColumnNames.CATEGORY_CODE + ", " +
                ColumnNames.CONTROL_CODE + ", " + ColumnNames.FIELD_NAME + ", " +
                ColumnNames.FIELD_VALUE + ", " + ColumnNames.PROCESS_STATUS + ") VALUES ");
        for (int i = 0; i < bucketValues.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("(?, ?, ?, ?, ?, ?)");
        }
        List<Object> params = new ArrayList<>();
        for (String value : bucketValues) {
            params.add(commandId);
            params.add(categoryCode);
            params.add(controlCode);
            params.add(splitFields);
            params.add(value);
            params.add(ColumnNames.STATUS_EMPTY);
        }
        getJdbc().update(sql.toString(), params.toArray());
        log.info("Created {} buckets for command {}", bucketValues.size(), commandId);
    }
}
