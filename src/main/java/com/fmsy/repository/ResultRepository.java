package com.fmsy.repository;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.model.Result;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.TableNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 结果表(结果表) Repository — 集中管理对结果表的全部 SQL 访问。
 *
 * <p>提供两类入口:
 * <ul>
 *   <li>{@link #insert(Result)} — 完整结果,实体承载全部字段(结果表行 + 路由 dbName)</li>
 *   <li>{@link #insertSimple(...)} — 异常/跳过/启动恢复等简化场景(仅 6 列)</li>
 * </ul>
 */
@Slf4j
@Repository
public class ResultRepository {

    private static final String SQL_INSERT_FULL =
        "INSERT INTO " + TableNames.RESULT_TABLE + " (" +
        ColumnNames.COMMAND_ID + ", " + ColumnNames.CATEGORY_CODE + ", " + ColumnNames.CONTROL_CODE + ", " +
        ColumnNames.FTP_NAME + ", " + ColumnNames.FILE_PATH + ", " + ColumnNames.DB_INFO + ", " +
        ColumnNames.TRANSFER_DATE + ", " + ColumnNames.RESULT + ", " + ColumnNames.PROCESS_START_TIME + ", " +
        ColumnNames.DURATION_MS + ", " + ColumnNames.RECORD_COUNT + ", " + ColumnNames.FILE_SIZE + ", " +
        ColumnNames.RESULT_DESC + ", " + ColumnNames.TRANSFER_DIRECTION + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_INSERT_SIMPLE =
        "INSERT INTO " + TableNames.RESULT_TABLE + " (" +
        ColumnNames.COMMAND_ID + ", " + ColumnNames.CATEGORY_CODE + ", " + ColumnNames.CONTROL_CODE + ", " +
        ColumnNames.RESULT + ", " + ColumnNames.RESULT_DESC + ", " + ColumnNames.TRANSFER_DATE + ") " +
        "VALUES (?, ?, ?, ?, ?, ?)";

    private final DataSourceConfig.DbPool dbPool;

    public ResultRepository(DataSourceConfig.DbPool dbPool) {
        this.dbPool = dbPool;
    }

    private JdbcTemplateWrapper getJdbc(String dbName) {
        return dbPool.resolveJdbcTemplate(dbName);
    }

    /**
     * 完整结果写入 — 实体承载全部字段(结果表 14 列 + 路由 dbName)。
     * 由 Result.markEnd() / transfer/download/SChildCommandProcessor.writeSubCommandResult 等调用方组装。
     */
    public void insert(Result result) {
        JdbcTemplateWrapper jdbc = getJdbc(result.getDbName());
        jdbc.update(SQL_INSERT_FULL,
            result.getCommandId(),
            result.getCategoryCode(),
            result.getControlCode(),
            result.getFtpName(),
            result.getFilePath(),
            result.getDbInfo(),
            result.getTransmissionDate(),
            result.getResult(),
            result.getStartTime(),
            result.getDurationMs(),
            result.getRecordCount(),
            result.getFileSize(),
            result.getDescription(),
            result.getTransferDirection()
        );
        log.info("Wrote result for command: {}, result: {}", result.getCommandId(), result.getResult());
    }

    /**
     * 简化结果写入 — 异常/跳过场景使用,仅写 6 列(指令ID/类别/控制/处理结果/说明/日期)。
     * 走指定数据源,dbName 为空时回退到 DEFAULT_DB。
     */
    public void insertSimple(Long commandId, String categoryCode, String controlCode,
                             String result, String description, String dbName) {
        JdbcTemplateWrapper jdbc = getJdbc(dbName != null && !dbName.isEmpty() ? dbName : ColumnNames.DEFAULT_DB);
        jdbc.update(SQL_INSERT_SIMPLE, commandId, categoryCode, controlCode, result,
                description, LocalDate.now());
        log.info("Wrote simple result for command: {}", commandId);
    }

    /**
     * 简化结果写入 — 走默认 DB。保留重载避免修改其他调用方。
     */
    public void insertSimple(Long commandId, String categoryCode, String controlCode,
                             String result, String description) {
        insertSimple(commandId, categoryCode, controlCode, result, description, ColumnNames.DEFAULT_DB);
    }
}
