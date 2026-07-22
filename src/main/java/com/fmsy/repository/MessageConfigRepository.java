package com.fmsy.repository;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.model.MessageConfig;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.TableNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息配置表 Repository — 集中管理对该表的全部 SQL 访问。
 *
 * <p>消息配置表按 (类别代号, 控制代号) 唯一标识，与传输配置共用代号体系。
 * 由 {@link com.fmsy.fileops.MessageConfigService} 加载到内存缓存。
 */
@Slf4j
@Repository
public class MessageConfigRepository {

    private static final String SQL_LOAD_ALL =
            "SELECT * FROM " + TableNames.MESSAGE_CONFIG_TABLE;

    private final DataSourceConfig.DbPool dbPool;

    public MessageConfigRepository(DataSourceConfig.DbPool dbPool) {
        this.dbPool = dbPool;
    }

    /**
     * 加载所有消息配置。
     */
    public List<MessageConfig> loadAll() {
        JdbcTemplateWrapper jdbc = dbPool.getJdbcTemplate(ColumnNames.DEFAULT_DB);
        List<MessageConfig> result = new ArrayList<>();
        try {
            jdbc.query(SQL_LOAD_ALL, (rs, rowNum) -> {
                MessageConfig config = new MessageConfig();
                config.setCategoryCode(rs.getString(ColumnNames.CATEGORY_CODE));
                config.setControlCode(rs.getString(ColumnNames.CONTROL_CODE));
                config.setChannelType(rs.getString(ColumnNames.MSG_CHANNEL_TYPE));
                config.setTarget(rs.getString(ColumnNames.MSG_TARGET));
                config.setMessageTemplate(rs.getString(ColumnNames.MSG_TEMPLATE));
                result.add(config);
                return config;
            });
        } catch (Exception e) {
            log.warn("Message config table may not exist yet: {}", e.getMessage());
        }
        return result;
    }
}