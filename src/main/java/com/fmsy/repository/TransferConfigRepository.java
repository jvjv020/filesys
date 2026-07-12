package com.fmsy.repository;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.enums.TransferScenario;
import com.fmsy.model.TransferConfig;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.TableNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 传输配置表(传输配置表) Repository — 集中管理对该表的全部 SQL 访问。
 *
 * <p>本类只负责把表行映射成 TransferConfig,加载到内存的逻辑保留在 ConfigLoaderService(编排层)。
 */
@Slf4j
@Repository
public class TransferConfigRepository {

    private static final String SQL_LOAD_ALL =
        "SELECT * FROM " + TableNames.TRANSFER_CONFIG_TABLE + " WHERE " + ColumnNames.STATUS + "=?";

    private final DataSourceConfig.DbPool dbPool;

    public TransferConfigRepository(DataSourceConfig.DbPool dbPool) {
        this.dbPool = dbPool;
    }

    /**
     * 加载所有有效配置(状态=VALID)。
     * 走默认 DB。
     */
    public List<TransferConfig> loadAll() {
        JdbcTemplateWrapper jdbc = dbPool.getJdbcTemplate(ColumnNames.DEFAULT_DB);
        List<TransferConfig> result = new ArrayList<>();
        jdbc.query(SQL_LOAD_ALL, (rs, rowNum) -> {
            TransferConfig config = new TransferConfig();
            config.setCategoryCode(rs.getString(ColumnNames.CATEGORY_CODE));
            config.setControlCode(rs.getString(ColumnNames.CONTROL_CODE));
            config.setScenario(TransferScenario.valueOf(rs.getString(ColumnNames.SCENARIO)));
            config.setDbName(rs.getString(ColumnNames.DB_NAME));
            config.setTableName(rs.getString(ColumnNames.TABLE_NAME));
            config.setFtpName(rs.getString(ColumnNames.FTP_NAME));
            config.setFilePath(rs.getString(ColumnNames.FILE_PATH));
            config.setParserType(rs.getString(ColumnNames.PARSER_TYPE));
            config.setPreOperations(rs.getString(ColumnNames.PRE_OPS));
            config.setPostOperations(rs.getString(ColumnNames.POST_OPS));
            config.setSplitFields(rs.getString(ColumnNames.SPLIT_FIELDS));
            result.add(config);
            return config;
        }, ColumnNames.STATUS_VALID);
        return result;
    }
}
