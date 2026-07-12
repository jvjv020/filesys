package com.fmsy.config;

import com.fmsy.util.ColumnNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DataSourceConfig Tests")
class DataSourceConfigTest {

    @Nested
    @DisplayName("DbConfig")
    class DbConfigTests {

        @Test
        @DisplayName("should build JDBC URL from host, port, database")
        void shouldBuildJdbcUrl() {
            DataSourceConfig.DbConfig config = new DataSourceConfig.DbConfig();
            config.setHost("10.0.0.1");
            config.setPort(5432);
            config.setDatabase("fmsy_db");
            assertEquals("jdbc:postgresql://10.0.0.1:5432/fmsy_db", config.getUrl());
        }

        @Test
        @DisplayName("should handle different hosts")
        void shouldHandleDifferentHosts() {
            DataSourceConfig.DbConfig config = new DataSourceConfig.DbConfig();
            config.setHost("db-server.internal");
            config.setPort(5432);
            config.setDatabase("testdb");
            assertEquals("jdbc:postgresql://db-server.internal:5432/testdb", config.getUrl());
        }

        @Test
        @DisplayName("should have null defaults for optional fields")
        void shouldHaveNullDefaultsForOptionalFields() {
            DataSourceConfig.DbConfig config = new DataSourceConfig.DbConfig();
            assertNull(config.getMaxActive());
            assertNull(config.getMinIdle());
            assertNull(config.getConnectionTimeout());
            assertNull(config.getValidationQuery());
        }
    }

    @Nested
    @DisplayName("DbPool")
    class DbPoolTests {

        private DataSourceConfig.DbPool pool;

        @BeforeEach
        void setUp() {
            pool = new DataSourceConfig.DbPool();
        }

        @Nested
        @DisplayName("addDataSource and getDataSource")
        class AddAndGetDataSourceTests {

            @Test
            @DisplayName("should store and retrieve DataSource by id")
            void shouldStoreAndRetrieve() {
                DataSource ds = new com.alibaba.druid.pool.DruidDataSource();
                pool.addDataSource("DB_MAIN", ds);
                assertSame(ds, pool.getDataSource("DB_MAIN"));
            }

            @Test
            @DisplayName("should return null for non-existent id")
            void shouldReturnNullForNonExistent() {
                assertNull(pool.getDataSource("NONEXISTENT"));
            }

            @Test
            @DisplayName("should list all data source ids")
            void shouldListIds() {
                pool.addDataSource("DB_1", new com.alibaba.druid.pool.DruidDataSource());
                pool.addDataSource("DB_2", new com.alibaba.druid.pool.DruidDataSource());
                assertEquals(2, pool.listIds().size());
                assertTrue(pool.listIds().contains("DB_1"));
                assertTrue(pool.listIds().contains("DB_2"));
            }
        }

        @Nested
        @DisplayName("getJdbcTemplate")
        class GetJdbcTemplateTests {

            @Test
            @DisplayName("should create JdbcTemplateWrapper on first access")
            void shouldCreateOnFirstAccess() {
                pool.addDataSource("DB_TEST", new com.alibaba.druid.pool.DruidDataSource());
                assertNotNull(pool.getJdbcTemplate("DB_TEST"));
            }

            @Test
            @DisplayName("should throw for non-existent id")
            void shouldThrowForNonExistentId() {
                assertThrows(IllegalArgumentException.class,
                        () -> pool.getJdbcTemplate("NONEXISTENT"));
            }

            @Test
            @DisplayName("should return cached instance on subsequent calls")
            void shouldReturnCachedInstance() {
                pool.addDataSource("DB_CACHE", new com.alibaba.druid.pool.DruidDataSource());
                var first = pool.getJdbcTemplate("DB_CACHE");
                var second = pool.getJdbcTemplate("DB_CACHE");
                assertSame(first, second);
            }
        }

        @Nested
        @DisplayName("resolveJdbcTemplate")
        class ResolveJdbcTemplateTests {

            @Test
            @DisplayName("should resolve null dbName to DEFAULT_DB")
            void shouldResolveNullToDefault() {
                pool.addDataSource(ColumnNames.DEFAULT_DB, new com.alibaba.druid.pool.DruidDataSource());
                assertNotNull(pool.resolveJdbcTemplate(null));
            }

            @Test
            @DisplayName("should resolve empty dbName to DEFAULT_DB")
            void shouldResolveEmptyToDefault() {
                pool.addDataSource(ColumnNames.DEFAULT_DB, new com.alibaba.druid.pool.DruidDataSource());
                assertNotNull(pool.resolveJdbcTemplate(""));
            }

            @Test
            @DisplayName("should resolve specific dbName to its own template")
            void shouldResolveSpecificDbName() {
                pool.addDataSource("SPECIFIC_DB", new com.alibaba.druid.pool.DruidDataSource());
                assertNotNull(pool.resolveJdbcTemplate("SPECIFIC_DB"));
            }

            @Test
            @DisplayName("should throw for non-existent resolved dbName")
            void shouldThrowForNonExistentResolved() {
                assertThrows(IllegalArgumentException.class,
                        () -> pool.resolveJdbcTemplate("NONEXISTENT"));
            }

            @Test
            @DisplayName("should throw when DEFAULT_DB not configured and null passed")
            void shouldThrowWhenDefaultNotConfigured() {
                assertThrows(IllegalArgumentException.class,
                        () -> pool.resolveJdbcTemplate(null));
            }
        }

        @Nested
        @DisplayName("getTransactionTemplate")
        class GetTransactionTemplateTests {

            @Test
            @DisplayName("should create transaction template for valid id")
            void shouldCreateForValidId() {
                pool.addDataSource("DB_TX", new com.alibaba.druid.pool.DruidDataSource());
                assertNotNull(pool.getTransactionTemplate("DB_TX"));
            }

            @Test
            @DisplayName("should resolve null to DEFAULT_DB for transaction template")
            void shouldResolveNullToDefault() {
                pool.addDataSource(ColumnNames.DEFAULT_DB, new com.alibaba.druid.pool.DruidDataSource());
                assertNotNull(pool.getTransactionTemplate(null));
            }

            @Test
            @DisplayName("should create new instance on each call")
            void shouldCreateNewInstanceEachCall() {
                pool.addDataSource("DB_TX", new com.alibaba.druid.pool.DruidDataSource());
                var first = pool.getTransactionTemplate("DB_TX");
                var second = pool.getTransactionTemplate("DB_TX");
                assertNotSame(first, second);
            }
        }
    }
}
