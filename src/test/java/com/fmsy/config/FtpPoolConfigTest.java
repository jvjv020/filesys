package com.fmsy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FtpPoolConfig Tests")
class FtpPoolConfigTest {

    @Nested
    @DisplayName("FtpConfig defaults")
    class FtpConfigDefaultsTests {

        private final FtpPoolConfig.FtpConfig config = new FtpPoolConfig.FtpConfig();

        @Test
        @DisplayName("should have default port 21")
        void defaultPort() {
            assertEquals(21, config.getPort());
        }

        @Test
        @DisplayName("should have default timeout 30000")
        void defaultTimeout() {
            assertEquals(30000, config.getTimeout());
        }

        @Test
        @DisplayName("should have non-null Pool with defaults")
        void defaultPool() {
            assertNotNull(config.getPool());
            assertEquals(10, config.getPool().getMaxTotal());
            assertEquals(300, config.getPool().getMaxIdleTimeSeconds());
        }

        @Test
        @DisplayName("should have non-null Failover with defaults")
        void defaultFailover() {
            assertNotNull(config.getFailover());
            assertFalse(config.getFailover().isEnabled());
            assertEquals(2, config.getFailover().getMaxRetries());
        }

        @Test
        @DisplayName("should have non-null HealthCheck with defaults")
        void defaultHealthCheck() {
            assertNotNull(config.getHealthCheck());
            assertFalse(config.getHealthCheck().isEnabled());
            assertEquals(30, config.getHealthCheck().getIntervalSeconds());
        }
    }

    @Nested
    @DisplayName("FtpConfig custom values")
    class FtpConfigCustomTests {

        @Test
        @DisplayName("should set and get all fields")
        void shouldSetAndGetAllFields() {
            FtpPoolConfig.FtpConfig config = new FtpPoolConfig.FtpConfig();
            config.setId("my-ftp");
            config.setHost("ftp.example.com");
            config.setPort(2121);
            config.setUsername("admin");
            config.setPassword("secret123");
            config.setTimeout(5000);

            assertEquals("my-ftp", config.getId());
            assertEquals("ftp.example.com", config.getHost());
            assertEquals(2121, config.getPort());
            assertEquals("admin", config.getUsername());
            assertEquals("secret123", config.getPassword());
            assertEquals(5000, config.getTimeout());
        }

        @Test
        @DisplayName("should exclude password from toString")
        void shouldExcludePasswordFromToString() {
            FtpPoolConfig.FtpConfig config = new FtpPoolConfig.FtpConfig();
            config.setPassword("supersecret");
            String str = config.toString();
            assertFalse(str.contains("supersecret"));
        }

        @Test
        @DisplayName("should set and get custom pool values")
        void shouldSetCustomPool() {
            FtpPoolConfig.FtpConfig config = new FtpPoolConfig.FtpConfig();
            config.getPool().setMaxTotal(20);
            config.getPool().setMaxIdleTimeSeconds(600);
            assertEquals(20, config.getPool().getMaxTotal());
            assertEquals(600, config.getPool().getMaxIdleTimeSeconds());
        }

        @Test
        @DisplayName("should enable failover and set retries")
        void shouldEnableFailover() {
            FtpPoolConfig.FtpConfig config = new FtpPoolConfig.FtpConfig();
            config.getFailover().setEnabled(true);
            config.getFailover().setMaxRetries(5);
            assertTrue(config.getFailover().isEnabled());
            assertEquals(5, config.getFailover().getMaxRetries());
        }

        @Test
        @DisplayName("should enable health check and set interval")
        void shouldEnableHealthCheck() {
            FtpPoolConfig.FtpConfig config = new FtpPoolConfig.FtpConfig();
            config.getHealthCheck().setEnabled(true);
            config.getHealthCheck().setIntervalSeconds(60);
            assertTrue(config.getHealthCheck().isEnabled());
            assertEquals(60, config.getHealthCheck().getIntervalSeconds());
        }
    }

    @Nested
    @DisplayName("FtpPoolConfig top-level")
    class FtpPoolConfigTopLevelTests {

        @Test
        @DisplayName("should set and get configs list")
        void shouldSetAndGetConfigs() {
            FtpPoolConfig config = new FtpPoolConfig();
            FtpPoolConfig.FtpConfig ftp1 = new FtpPoolConfig.FtpConfig();
            ftp1.setId("ftp-1");
            FtpPoolConfig.FtpConfig ftp2 = new FtpPoolConfig.FtpConfig();
            ftp2.setId("ftp-2");
            config.setConfigs(List.of(ftp1, ftp2));

            assertNotNull(config.getConfigs());
            assertEquals(2, config.getConfigs().size());
            assertEquals("ftp-1", config.getConfigs().get(0).getId());
            assertEquals("ftp-2", config.getConfigs().get(1).getId());
        }

        @Test
        @DisplayName("should allow null configs list")
        void shouldAllowNullConfigs() {
            FtpPoolConfig config = new FtpPoolConfig();
            config.setConfigs(null);
            assertNull(config.getConfigs());
        }

        @Test
        @DisplayName("should allow empty configs list")
        void shouldAllowEmptyConfigs() {
            FtpPoolConfig config = new FtpPoolConfig();
            config.setConfigs(List.of());
            assertTrue(config.getConfigs().isEmpty());
        }
    }

    @Nested
    @DisplayName("inner class defaults")
    class InnerClassDefaultsTests {

        @Test
        @DisplayName("Pool: should have maxTotal=10, maxIdleTimeSeconds=300")
        void poolDefaults() {
            FtpPoolConfig.FtpConfig.Pool pool = new FtpPoolConfig.FtpConfig.Pool();
            assertEquals(10, pool.getMaxTotal());
            assertEquals(300, pool.getMaxIdleTimeSeconds());
        }

        @Test
        @DisplayName("Failover: should have enabled=false, maxRetries=2")
        void failoverDefaults() {
            FtpPoolConfig.FtpConfig.Failover failover = new FtpPoolConfig.FtpConfig.Failover();
            assertFalse(failover.isEnabled());
            assertEquals(2, failover.getMaxRetries());
        }

        @Test
        @DisplayName("HealthCheck: should have enabled=false, intervalSeconds=30")
        void healthCheckDefaults() {
            FtpPoolConfig.FtpConfig.HealthCheck hc = new FtpPoolConfig.FtpConfig.HealthCheck();
            assertFalse(hc.isEnabled());
            assertEquals(30, hc.getIntervalSeconds());
        }
    }
}
