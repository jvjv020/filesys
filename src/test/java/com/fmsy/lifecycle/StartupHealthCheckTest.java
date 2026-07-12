package com.fmsy.lifecycle;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.config.FtpPoolConfig;
import com.fmsy.ftp.FtpPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StartupHealthCheck Tests")
class StartupHealthCheckTest {

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private FtpPool ftpPool;

    @Mock
    private FtpPoolConfig ftpPoolConfig;

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    private StartupHealthCheck healthCheck;

    @BeforeEach
    void setUp() {
        healthCheck = new StartupHealthCheck(dbPool, ftpPool, ftpPoolConfig);
    }

    @Nested
    @DisplayName("healthCheck with all healthy")
    class AllHealthyTests {

        @Test
        @DisplayName("should pass when all DB and FTP are reachable")
        void shouldPassWhenAllHealthy() throws Exception {
            when(dbPool.listIds()).thenReturn(Set.of("DB1"));
            when(dbPool.getDataSource("DB1")).thenReturn(dataSource);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(5)).thenReturn(true);
            when(ftpPoolConfig.getConfigs()).thenReturn(Collections.emptyList());

            // Should not call System.exit
            healthCheck.healthCheck();

            verify(connection).close();
        }

        @Test
        @DisplayName("should check all DBs and FTPs")
        void shouldCheckAllDbsAndFtps() throws Exception {
            when(dbPool.listIds()).thenReturn(Set.of("DB1", "DB2"));
            when(dbPool.getDataSource("DB1")).thenReturn(dataSource);
            when(dbPool.getDataSource("DB2")).thenReturn(dataSource);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(5)).thenReturn(true);

            FtpPoolConfig.FtpConfig ftp1 = new FtpPoolConfig.FtpConfig();
            ftp1.setId("FTP1");
            ftp1.setHost("ftp.example.com");
            FtpPoolConfig.FtpConfig ftp2 = new FtpPoolConfig.FtpConfig();
            ftp2.setId("FTP2");
            ftp2.setHost("ftp2.example.com");
            when(ftpPoolConfig.getConfigs()).thenReturn(Arrays.asList(ftp1, ftp2));
            when(ftpPool.ping("FTP1")).thenReturn(true);
            when(ftpPool.ping("FTP2")).thenReturn(true);

            healthCheck.healthCheck();

            verify(dbPool).getDataSource("DB1");
            verify(dbPool).getDataSource("DB2");
            verify(ftpPool).ping("FTP1");
            verify(ftpPool).ping("FTP2");
        }
    }

    @Nested
    @DisplayName("DB failure scenarios (via reflection to avoid System.exit)")
    class DbFailureTests {

        @Test
        @DisplayName("should report failure when DataSource is null")
        void shouldFailWhenDataSourceNull() throws Exception {
            when(dbPool.listIds()).thenReturn(Set.of("DB1"));
            when(dbPool.getDataSource("DB1")).thenReturn(null);

            java.util.List<String> failures = invokeCheckDatabases();

            assertFalse(failures.isEmpty());
            assertTrue(failures.get(0).contains("DataSource is null"));
        }

        @Test
        @DisplayName("should report failure when connection isValid returns false")
        void shouldFailWhenConnectionInvalid() throws Exception {
            when(dbPool.listIds()).thenReturn(Set.of("DB1"));
            when(dbPool.getDataSource("DB1")).thenReturn(dataSource);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(5)).thenReturn(false);

            java.util.List<String> failures = invokeCheckDatabases();

            assertFalse(failures.isEmpty());
            assertTrue(failures.get(0).contains("isValid returned false"));
        }

        @Test
        @DisplayName("should report failure when connection throws exception")
        void shouldFailWhenConnectionThrows() throws Exception {
            when(dbPool.listIds()).thenReturn(Set.of("DB1"));
            when(dbPool.getDataSource("DB1")).thenReturn(dataSource);
            when(dataSource.getConnection()).thenThrow(new RuntimeException("Connection refused"));

            java.util.List<String> failures = invokeCheckDatabases();

            assertFalse(failures.isEmpty());
            assertTrue(failures.get(0).contains("Connection refused"));
        }

        @Test
        @DisplayName("should check all DBs even if some fail")
        void shouldCheckAllDbsEvenIfSomeFail() throws Exception {
            when(dbPool.listIds()).thenReturn(Set.of("DB1", "DB2"));

            DataSource badDs = mock(DataSource.class);
            when(badDs.getConnection()).thenThrow(new RuntimeException("Connection refused"));

            when(dbPool.getDataSource("DB1")).thenReturn(badDs);
            when(dbPool.getDataSource("DB2")).thenReturn(dataSource);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(5)).thenReturn(true);

            java.util.List<String> failures = invokeCheckDatabases();

            verify(dbPool).getDataSource("DB1");
            verify(dbPool).getDataSource("DB2");
            assertEquals(1, failures.size());
        }
    }

    /** Invoke private checkDatabases method via reflection */
    @SuppressWarnings("unchecked")
    private java.util.List<String> invokeCheckDatabases() throws Exception {
        var method = StartupHealthCheck.class.getDeclaredMethod("checkDatabases", java.util.List.class);
        method.setAccessible(true);
        java.util.List<String> failures = new java.util.ArrayList<>();
        method.invoke(healthCheck, failures);
        return failures;
    }

    @Nested
    @DisplayName("FTP failure scenarios")
    class FtpFailureTests {

        @Test
        @DisplayName("should warn when FTP is unreachable but not exit")
        void shouldWarnWhenFtpUnreachable() throws Exception {
            when(dbPool.listIds()).thenReturn(Set.of("DB1"));
            when(dbPool.getDataSource("DB1")).thenReturn(dataSource);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(5)).thenReturn(true);

            FtpPoolConfig.FtpConfig ftp1 = new FtpPoolConfig.FtpConfig();
            ftp1.setId("FTP1");
            ftp1.setHost("ftp.example.com");
            when(ftpPoolConfig.getConfigs()).thenReturn(List.of(ftp1));
            when(ftpPool.ping("FTP1")).thenReturn(false);

            // Should NOT call System.exit (FTP failure is non-fatal)
            healthCheck.healthCheck();

            verify(ftpPool).ping("FTP1");
        }

        @Test
        @DisplayName("should handle FTP ping exception")
        void shouldHandleFtpPingException() throws Exception {
            when(dbPool.listIds()).thenReturn(Set.of("DB1"));
            when(dbPool.getDataSource("DB1")).thenReturn(dataSource);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(5)).thenReturn(true);

            FtpPoolConfig.FtpConfig ftp1 = new FtpPoolConfig.FtpConfig();
            ftp1.setId("FTP1");
            ftp1.setHost("ftp.example.com");
            when(ftpPoolConfig.getConfigs()).thenReturn(List.of(ftp1));
            when(ftpPool.ping("FTP1")).thenThrow(new RuntimeException("Timeout"));

            // Should NOT call System.exit
            healthCheck.healthCheck();
        }

        @Test
        @DisplayName("should handle null FTP configs")
        void shouldHandleNullFtpConfigs() throws Exception {
            when(dbPool.listIds()).thenReturn(Set.of("DB1"));
            when(dbPool.getDataSource("DB1")).thenReturn(dataSource);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(5)).thenReturn(true);
            when(ftpPoolConfig.getConfigs()).thenReturn(null);

            healthCheck.healthCheck();
        }
    }

    @Nested
    @DisplayName("FtpConfig getter/setter")
    class FtpConfigAccessorTests {

        @Test
        @DisplayName("should set and get properties correctly")
        void shouldSetAndGetProperties() {
            FtpPoolConfig.FtpConfig config = new FtpPoolConfig.FtpConfig();
            config.setId("ftp1");
            config.setHost("host1");
            config.setPort(21);
            config.setUsername("user");
            config.setPassword("pass");

            assertEquals("ftp1", config.getId());
            assertEquals("host1", config.getHost());
            assertEquals(21, config.getPort());
            assertEquals("user", config.getUsername());
            assertEquals("pass", config.getPassword());
        }

        @Test
        @DisplayName("should have default pool and failover")
        void shouldHaveDefaults() {
            FtpPoolConfig.FtpConfig config = new FtpPoolConfig.FtpConfig();

            assertNotNull(config.getPool());
            assertEquals(10, config.getPool().getMaxTotal());
            assertNotNull(config.getFailover());
            assertFalse(config.getFailover().isEnabled());
            assertNotNull(config.getHealthCheck());
            assertFalse(config.getHealthCheck().isEnabled());
        }
    }
}
