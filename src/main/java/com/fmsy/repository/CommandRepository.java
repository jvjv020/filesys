package com.fmsy.repository;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.enums.CommandType;
import com.fmsy.model.Command;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.TableNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 指令表(指令表) Repository — 集中管理对该表的全部 SQL 访问。
 *
 * <p>主要调用方:
 * <ul>
 *   <li>{@code polling/PollingService} — releaseTimeout / findTimeoutJobs / loadProcessing / findReadyCommands</li>
 *   <li>{@code lifecycle/StartupService} — findProcessingJobs(异常恢复)/ updateStatus</li>
 *   <li>{@code transfer/download/ChildCommandMonitor} — countCompletedChildren / updateMainStatus / updateMainStatusOnTimeout</li>
 *   <li>{@code transfer/TransferService} — findById</li>
 * </ul>
 */
@Slf4j
@Repository
public class CommandRepository {

    private final DataSourceConfig.DbPool dbPool;
    private final ResultRepository resultRepository;

    public CommandRepository(DataSourceConfig.DbPool dbPool, ResultRepository resultRepository) {
        this.dbPool = dbPool;
        this.resultRepository = resultRepository;
    }

    private JdbcTemplateWrapper getJdbc() {
        return dbPool.getJdbcTemplate(ColumnNames.DEFAULT_DB);
    }

    // ==================== 健康检查 ====================

    private static final String SQL_PROBE = "SELECT 1";

    /**
     * 数据库连通性探测(纯 "SELECT 1",与具体业务表无关)。
     * 启动期调用,失败不抛异常,由调用方决定是否阻断启动。
     */
    public Integer probeDatabase() {
        return getJdbc().queryForObject(SQL_PROBE, Integer.class);
    }

    // ==================== 状态更新 ====================

    private static final String SQL_UPDATE_STATUS =
        "UPDATE " + TableNames.COMMAND_TABLE + " SET " +
        ColumnNames.PROCESS_STATUS + "=?, " + ColumnNames.PROCESS_END_TIME + "=NOW() " +
        "WHERE " + ColumnNames.ID + "=?";

    /** 更新命令状态(同步结束时间) */
    public void updateStatus(Long commandId, String status) {
        getJdbc().update(SQL_UPDATE_STATUS, status, commandId);
        log.info("Updated command status: id={}, status={}", commandId, status);
    }

    /**
     * 一站式"配置缺失 → 置 ERROR + 写结果表"。把 3 处重复(updateStatus + insertSimple + 相同描述)合并为一个调用。
     * 由 {@code transfer.TransferService.process} / {@code polling.BatchDispatcher.dispatch} / 同类
     * 失败路径使用,统一语义:"config not found, mark ERROR + record result"。
     *
     * @param commandId    主命令/子命令 ID
     * @param categoryCode 类别代号(可空)
     * @param controlCode  控制代号(可空)
     * @param description  结果表 description(通常为 "Config not found: ...")
     */
    public void markErrorWithResult(Long commandId, String categoryCode, String controlCode, String description) {
        updateStatus(commandId, ColumnNames.STATUS_ERROR);
        resultRepository.insertSimple(commandId, categoryCode, controlCode,
                ColumnNames.STATUS_ERROR, description);
    }

    // ==================== 竞争 ====================

    private static final String SQL_COMPETE_COMMAND =
        "UPDATE " + TableNames.COMMAND_TABLE + " SET " +
        ColumnNames.PROCESSING_NODE + "=?, " + ColumnNames.PROCESS_START_TIME + "=NOW(), " +
        ColumnNames.PROCESS_STATUS + "=? " +
        "WHERE " + ColumnNames.ID + "=? AND " + ColumnNames.PROCESS_STATUS + "=? AND " +
        ColumnNames.PROCESSING_NODE + " IS NULL";

    /**
     * 原子竞争 — 单条原子 UPDATE:把本节点 ID 写入选中行的 processing_node,
     * 影响 1 行才返回 true(已被其他节点抢走 → false)。
     *
     * @return true=竞争成功(影响 1 行),false=竞争失败或数据库异常
     */
    public boolean compete(Long commandId, String nodeId) {
        try {
            int affected = getJdbc().update(SQL_COMPETE_COMMAND, nodeId, ColumnNames.STATUS_PROCESSING,
                    commandId, ColumnNames.STATUS_EMPTY);
            return affected == 1;
        } catch (DataAccessException e) {
            log.error("Failed to compete command {} on node {}: {}",
                    commandId, nodeId, e.getMessage(), e);
            return false;
        }
    }

    // ==================== 超时释放 ====================

    private static final String SQL_RELEASE_TIMEOUT =
        "UPDATE " + TableNames.COMMAND_TABLE + " SET " + ColumnNames.PROCESS_STATUS + "=?, " +
        ColumnNames.PROCESS_END_TIME + "=NOW() " +
        "WHERE " + ColumnNames.PROCESS_STATUS + "=? AND " + ColumnNames.PROCESSING_NODE + "=? AND " +
        ColumnNames.PROCESS_START_TIME + " < NOW() - INTERVAL %d HOUR";

    private static final String SQL_FIND_TIMEOUT_JOBS =
        "SELECT " + ColumnNames.ID + ", " + ColumnNames.CATEGORY_CODE + ", " +
        ColumnNames.CONTROL_CODE + " FROM " + TableNames.COMMAND_TABLE +
        " WHERE " + ColumnNames.PROCESS_STATUS + "=? AND " + ColumnNames.PROCESSING_NODE + "=? AND " +
        ColumnNames.PROCESS_END_TIME + " >= NOW() - INTERVAL %d HOUR";

    /**
     * 释放超时任务(将超过 hours 小时仍处于 P 状态的本节点命令置为 newStatus)。
     */
    public void releaseTimeoutCommands(String nodeId, int hours, String newStatus) {
        String sql = String.format(SQL_RELEASE_TIMEOUT, hours);
        getJdbc().update(sql, newStatus, ColumnNames.STATUS_PROCESSING, nodeId);
    }

    /**
     * 查询本轮被释放的超时任务(供结果表写入使用)。
     */
    public List<Map<String, Object>> findTimeoutJobs(String nodeId, int hours) {
        String sql = String.format(SQL_FIND_TIMEOUT_JOBS, hours);
        return getJdbc().queryForList(sql, ColumnNames.STATUS_ERROR, nodeId);
    }

    // ==================== 内存串行约束(加载处理中命令) ====================

    private static final String SQL_LOAD_PROCESSING =
        "SELECT " + ColumnNames.ID + ", " + ColumnNames.PROCESSING_NODE + ", " + ColumnNames.CATEGORY_CODE + ", " +
        ColumnNames.CONTROL_CODE + ", " + ColumnNames.COMMAND_TYPE + ", " + ColumnNames.EXTRA_INFO +
        " FROM " + TableNames.COMMAND_TABLE + " WHERE " + ColumnNames.PROCESS_STATUS + "=? AND " +
        ColumnNames.PROCESSING_NODE + " IS NOT NULL";

    /**
     * 加载当前所有 P 状态命令(供内存串行约束检查使用)。
     */
    public List<Map<String, Object>> findProcessingCommands() {
        return getJdbc().queryForList(SQL_LOAD_PROCESSING, ColumnNames.STATUS_PROCESSING);
    }

    // ==================== 待处理命令(轮询入口) ====================

    private static final String SQL_FIND_READY_COMMANDS =
        "SELECT " + ColumnNames.ID + ", " + ColumnNames.CATEGORY_CODE + ", " +
        ColumnNames.CONTROL_CODE + ", " + ColumnNames.COMMAND_TYPE + ", " +
        ColumnNames.AUDIT_COUNT + ", " + ColumnNames.EXTRA_INFO + ", " + ColumnNames.TEMP_CONFIG +
        " FROM (SELECT " + ColumnNames.ID + ", " + ColumnNames.CATEGORY_CODE + ", " +
        ColumnNames.CONTROL_CODE + ", " + ColumnNames.COMMAND_TYPE + ", " +
        ColumnNames.AUDIT_COUNT + ", " + ColumnNames.EXTRA_INFO + ", " + ColumnNames.TEMP_CONFIG + ", " +
        "ROW_NUMBER() OVER (PARTITION BY " + ColumnNames.CATEGORY_CODE + ", " +
        ColumnNames.CONTROL_CODE + " ORDER BY " + ColumnNames.ID + " ASC) AS rn" +
        " FROM " + TableNames.COMMAND_TABLE + " WHERE " + ColumnNames.PROCESS_STATUS + "=? AND " +
        ColumnNames.PROCESSING_NODE + " IS NULL) t WHERE rn = 1 ORDER BY " + ColumnNames.ID + " ASC LIMIT ?";

    /**
     * 查询待处理命令(空状态,同 类别+控制 仅取最早一条)。
     */
    public List<Command> findReadyCommands(int limit) {
        RowMapper<Command> mapper = (rs, rowNum) -> {
            Command cmd = new Command();
            cmd.setId(rs.getLong(ColumnNames.ID));
            cmd.setCategoryCode(rs.getString(ColumnNames.CATEGORY_CODE));
            cmd.setControlCode(rs.getString(ColumnNames.CONTROL_CODE));
            String type = rs.getString(ColumnNames.COMMAND_TYPE);
            cmd.setCommandType(type == null ? null : CommandType.valueOf(type));
            cmd.setAuditCount(rs.getInt(ColumnNames.AUDIT_COUNT));
            cmd.setExtraInfo(rs.getString(ColumnNames.EXTRA_INFO));
            cmd.setTempConfig(rs.getString(ColumnNames.TEMP_CONFIG));
            return cmd;
        };
        return getJdbc().query(SQL_FIND_READY_COMMANDS, mapper, ColumnNames.STATUS_EMPTY, limit);
    }

    // ==================== 启动异常恢复 ====================

    private static final String SQL_FIND_PROCESSING_JOBS =
        "SELECT " + ColumnNames.ID + ", " + ColumnNames.CATEGORY_CODE + ", " +
        ColumnNames.CONTROL_CODE + ", " + ColumnNames.COMMAND_TYPE + ", " +
        ColumnNames.PROCESSING_NODE + " FROM " + TableNames.COMMAND_TABLE +
        " WHERE " + ColumnNames.PROCESS_STATUS + "=? AND (" +
        ColumnNames.PROCESSING_NODE + "=? OR " + ColumnNames.PROCESSING_NODE + " IS NULL)";

    /**
     * 查询所有 P 状态命令(本节点 + 节点为空的)。供启动异常恢复使用。
     */
    public List<Map<String, Object>> findProcessingJobs(String nodeId) {
        return getJdbc().queryForList(SQL_FIND_PROCESSING_JOBS, ColumnNames.STATUS_PROCESSING, nodeId);
    }

    // ==================== 主命令状态更新(子命令监控) ====================

    private static final String SQL_UPDATE_MAIN_STATUS =
        "UPDATE " + TableNames.COMMAND_TABLE + " SET " +
        ColumnNames.PROCESS_STATUS + "=?, " + ColumnNames.PROCESS_END_TIME + "=NOW() " +
        "WHERE " + ColumnNames.ID + "=?";

    /** 更新主命令状态(子命令监控完成后调用) */
    public void updateMainStatus(Long mainCommandId, String status) {
        getJdbc().update(SQL_UPDATE_MAIN_STATUS, status, mainCommandId);
    }

    // ==================== 子命令查询(多节点协调) ====================

    private static final String SQL_COUNT_COMPLETED_CHILDREN =
        "SELECT COUNT(*) FROM " + TableNames.COMMAND_TABLE +
        " WHERE " + ColumnNames.EXTRA_INFO + " LIKE ? AND " + ColumnNames.COMMAND_TYPE + "=? AND " +
        ColumnNames.PROCESS_STATUS + " IN (?, ?, ?)";

    // 迭代 #17:子命令 EXTRA_INFO 写入 "mainId|baseFilePath" 格式,把主命令已固化的
    // baseFilePath 透传给 S 型子命令,供子节点后续 executeSingleNode 直接复用
    // (避免子节点再次 resolveFilePath 时 EXTRA_INFO / 日期占位符语义漂移)。
    private static final String SQL_CREATE_CHILD_COMMAND =
        "INSERT INTO " + TableNames.COMMAND_TABLE + " (" +
        ColumnNames.CATEGORY_CODE + ", " + ColumnNames.CONTROL_CODE + ", " +
        ColumnNames.COMMAND_TYPE + ", " + ColumnNames.EXTRA_INFO + ", " +
        ColumnNames.AUDIT_COUNT + ", " + ColumnNames.PROCESS_STATUS + ") VALUES (?, ?, 'S', ?, ?, ?)";

    /**
     * 创建 S 型子命令。
     *
     * @param categoryCode 类别代号
     * @param controlCode  控制代号
     * @param extraInfo    "mainId|baseFilePath" 格式
     * @param auditCount   稽核数(传 -1 表示不预填)
     * @return 影响行数(0 / 1)
     */
    public int createChildCommand(String categoryCode, String controlCode,
                                  String extraInfo, int auditCount) {
        return getJdbc().update(SQL_CREATE_CHILD_COMMAND, categoryCode, controlCode, extraInfo,
                auditCount, ColumnNames.STATUS_EMPTY);
    }

    /**
     * 批量创建 S 型子命令 — 使用单条多值 INSERT 减少网络往返。
     *
     * <p>生成 {@code INSERT INTO 指令表 (cat, ctrl, 'S', extraInfo, auditCount, '')
     * VALUES (?, ?, 'S', ?, ?, ''), (?, ?, 'S', ?, ?, ''), ...}
     * 然后一次 {@code update()} 完成 N 行插入。
     *
     * @param count        创建数量
     * @param categoryCode 类别代号
     * @param controlCode  控制代号
     * @param extraInfo    "mainId|baseFilePath" 格式
     * @param auditCount   稽核数(传 -1 表示不预填)
     */
    public void batchCreateChildCommands(int count, String categoryCode, String controlCode,
                                         String extraInfo, int auditCount) {
        if (count <= 0) return;
        String rowPlaceholder = "(?, ?, 'S', ?, ?, ?)";
        StringBuilder sql = new StringBuilder(
                "INSERT INTO " + TableNames.COMMAND_TABLE + " (" +
                ColumnNames.CATEGORY_CODE + ", " + ColumnNames.CONTROL_CODE + ", " +
                ColumnNames.COMMAND_TYPE + ", " + ColumnNames.EXTRA_INFO + ", " +
                ColumnNames.AUDIT_COUNT + ", " + ColumnNames.PROCESS_STATUS + ") VALUES ");
        for (int i = 0; i < count; i++) {
            if (i > 0) sql.append(", ");
            sql.append(rowPlaceholder);
        }
        Object[] params = new Object[count * 5];
        int idx = 0;
        for (int i = 0; i < count; i++) {
            params[idx++] = categoryCode;
            params[idx++] = controlCode;
            params[idx++] = extraInfo;
            params[idx++] = auditCount;
            params[idx++] = ColumnNames.STATUS_EMPTY;
        }
        getJdbc().update(sql.toString(), params);
        log.debug("Batch created {} child command(s) for {}|{}", count, categoryCode, controlCode);
    }

    /**
     * 统计某主命令的已完成子命令数量(Y/N/E 任一即可)。
     * likePrefix 形如 "mainId|%"(Iteration #17 协议)。
     */
    public int countCompletedChildren(String likePrefix) {
        Integer count = getJdbc().queryForObject(SQL_COUNT_COMPLETED_CHILDREN, Integer.class,
                likePrefix, CommandType.COORDINATED.code(),
                ColumnNames.STATUS_SUCCESS, ColumnNames.STATUS_SKIPPED, ColumnNames.STATUS_ERROR);
        return count != null ? count : 0;
    }

    // ==================== 简单查询 ====================

    private static final String SQL_FIND_BY_ID =
        "SELECT " + ColumnNames.ID + ", " + ColumnNames.CATEGORY_CODE + ", " +
        ColumnNames.CONTROL_CODE + ", " + ColumnNames.COMMAND_TYPE + ", " +
        ColumnNames.AUDIT_COUNT + ", " + ColumnNames.EXTRA_INFO + ", " + ColumnNames.TEMP_CONFIG +
        " FROM " + TableNames.COMMAND_TABLE + " WHERE " + ColumnNames.ID + "=?";

    /**
     * 按 ID 查询命令基本信息(类别 / 控制 / 类型 / 稽核数 / 额外信息)。
     * 命令不存在时返回 null。
     */
    public Command findById(Long commandId) {
        List<Map<String, Object>> rows = getJdbc().queryForList(SQL_FIND_BY_ID, commandId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Command cmd = new Command();
        cmd.setId(((Number) row.get(ColumnNames.ID)).longValue());
        cmd.setCategoryCode((String) row.get(ColumnNames.CATEGORY_CODE));
        cmd.setControlCode((String) row.get(ColumnNames.CONTROL_CODE));
        String type = (String) row.get(ColumnNames.COMMAND_TYPE);
        cmd.setCommandType(type == null ? null : CommandType.fromCode(type));
        cmd.setAuditCount(row.get(ColumnNames.AUDIT_COUNT) != null
                ? ((Number) row.get(ColumnNames.AUDIT_COUNT)).intValue() : null);
        cmd.setExtraInfo((String) row.get(ColumnNames.EXTRA_INFO));
        cmd.setTempConfig((String) row.get(ColumnNames.TEMP_CONFIG));
        return cmd;
    }
}
