package com.fmsy.config;

import com.alibaba.druid.pool.DruidDataSource;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fmsy.db.JdbcTemplateWrapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多数据源配置
 *
 * 功能说明：
 * - 支持配置多个数据库连接（Druid连接池）
 * - 通过DbPool管理多个DataSource
 * - 按ID获取对应的JdbcTemplate
 *
 * 配置来源：
 * - database.config开头的配置项
 * - 每个配置包含：ID、主机、端口、数据库名、用户名、密码
 */
@Slf4j
@Configuration
public class DataSourceConfig {

    /** 创建数据库连接池 */
    @Bean
    public DbPool dbPool(List<DbConfig> configs) {
        DbPool pool = new DbPool();
        for (DbConfig config : configs) {
            DruidDataSource dataSource = new DruidDataSource();
            dataSource.setUrl(config.getUrl());
            dataSource.setUsername(config.getUsername());
            dataSource.setPassword(config.getPassword());
            dataSource.setMaxActive(config.getMaxActive() != null ? config.getMaxActive() : 10);
            dataSource.setMinIdle(config.getMinIdle() != null ? config.getMinIdle() : 2);
            dataSource.setMaxWait(config.getConnectionTimeout() != null ? config.getConnectionTimeout() : 30000L);
            dataSource.setValidationQuery(config.getValidationQuery() != null ? config.getValidationQuery() : "SELECT 1");
            pool.addDataSource(config.getId(), dataSource);
        }
        return pool;
    }

    /** 单个数据库配置项 */
    @Data
    @ConfigurationProperties(prefix = "database.config")
    public static class DbConfig {
        private String id;
        private String host;
        private int port;
        private String database;
        private String username;
        private String password;
        private Integer maxActive;
        private Integer minIdle;
        private Long connectionTimeout;
        private String validationQuery;

        /** 构建PostgreSQL JDBC连接URL */
        public String getUrl() {
            return String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
        }
    }

    /** 数据库连接池管理 */
    public static class DbPool {
        private final Map<String, DataSource> dataSources = new HashMap<>();
        private final Map<String, DataSourceTransactionManager> txManagers = new ConcurrentHashMap<>();
        private final Map<String, JdbcTemplateWrapper> jdbcTemplates = new ConcurrentHashMap<>();

        /** 添加数据源 */
        public void addDataSource(String id, DataSource dataSource) {
            dataSources.put(id, dataSource);
        }

        /** 按ID获取数据源 */
        public DataSource getDataSource(String id) {
            return dataSources.get(id);
        }

        /**
         * 按ID获取JdbcTemplate（懒创建并缓存）。
         * 避免每次调用都 new JdbcTemplate(ds) 丢失其内部 Statement 缓存。
         */
        public JdbcTemplateWrapper getJdbcTemplate(String id) {
            return jdbcTemplates.computeIfAbsent(id, k -> {
                DataSource ds = dataSources.get(k);
                if (ds == null) {
                    throw new IllegalArgumentException("DataSource not found: " + k);
                }
                return new JdbcTemplateWrapper(new org.springframework.jdbc.core.JdbcTemplate(ds));
            });
        }

        /**
         * 按 dbName 获取 JdbcTemplate,空值 / null 回退到默认 DB({@link com.fmsy.util.ColumnNames#DEFAULT_DB})。
         * 统一替代 3 处重复的 {@code if (dbName == null || dbName.isEmpty()) dbName = DEFAULT_DB;} 模式。
         */
        public JdbcTemplateWrapper resolveJdbcTemplate(String dbName) {
            String resolved = (dbName == null || dbName.isEmpty())
                    ? com.fmsy.util.ColumnNames.DEFAULT_DB : dbName;
            return getJdbcTemplate(resolved);
        }

        /**
         * 按 ID 懒创建并缓存 {@link DataSourceTransactionManager}。
         * 事务管理器与 DataSource 一一对应 — 同一 dbName 下所有 JdbcTemplate
         * 操作都会参与该 manager 开启的事务(经由 {@code DataSourceUtils.getConnection}
         * 自动绑定连接)。
         */
        private DataSourceTransactionManager getTransactionManager(String id) {
            return txManagers.computeIfAbsent(id, k -> {
                DataSource ds = dataSources.get(k);
                if (ds == null) {
                    throw new IllegalArgumentException("DataSource not found: " + k);
                }
                return new DataSourceTransactionManager(ds);
            });
        }

        /**
         * 为指定 dbName 创建一个 {@link TransactionTemplate}。
         * 不缓存:TransactionTemplate 本身无状态,每次新建开销可忽略,
         * 缓存反而会带来跨 Handler 共享可变配置的隐式耦合。
         */
        public TransactionTemplate getTransactionTemplate(String id) {
            String resolved = (id == null || id.isEmpty())
                    ? com.fmsy.util.ColumnNames.DEFAULT_DB : id;
            return new TransactionTemplate(getTransactionManager(resolved));
        }

        /** 列出所有数据源 ID(用于健康检查) */
        public java.util.Set<String> listIds() {
            return dataSources.keySet();
        }

        /**
         * 关闭所有数据源 - P1#4 UC-23:JVM 退出时关闭连接池
         *
         * <p>绑定到 Spring 容器销毁钩子(@PreDestroy),
         * 配合 {@link com.fmsy.ftp.FtpPool#close()} 实现完整资源回收。
         */
        @PreDestroy
        public void close() {
            log.info("Closing DbPool: {} data source(s)", dataSources.size());
            for (Map.Entry<String, DataSource> entry : dataSources.entrySet()) {
                String id = entry.getKey();
                DataSource ds = entry.getValue();
                if (ds instanceof DruidDataSource druidDs) {
                    try {
                        druidDs.close();
                        log.info("Closed DruidDataSource: {}", id);
                    } catch (Exception e) {
                        log.warn("Failed to close DruidDataSource {}: {}", id, e.getMessage());
                    }
                } else {
                    log.warn("DataSource {} is not DruidDataSource ({}), skipping close",
                            id, ds.getClass().getName());
                }
            }
            dataSources.clear();
        }
    }
}