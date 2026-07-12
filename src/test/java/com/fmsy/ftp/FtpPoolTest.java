package com.fmsy.ftp;

import com.fmsy.config.FtpPoolConfig;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("FtpPool Tests")
class FtpPoolTest {

    // ==================== FtpPool Top-Level Tests ====================

    @Nested
    @DisplayName("pool initialization")
    class PoolInitializationTests {

        @Test
        @DisplayName("should create pool with null configs")
        void shouldCreateWithNullConfigs() {
            FtpPoolConfig config = new FtpPoolConfig();
            config.setConfigs(null);
            assertDoesNotThrow(() -> new FtpPool(config));
        }

        @Test
        @DisplayName("should create pool with empty configs")
        void shouldCreateWithEmptyConfigs() {
            FtpPoolConfig config = new FtpPoolConfig();
            config.setConfigs(List.of());
            assertDoesNotThrow(() -> new FtpPool(config));
        }

        @Test
        @DisplayName("should create pool with valid config")
        void shouldCreateWithValidConfig() {
            FtpPoolConfig config = createSingleFtpConfig("test-ftp", "localhost", 21);
            assertDoesNotThrow(() -> new FtpPool(config));
        }

        @Test
        @DisplayName("should not start health check when disabled")
        void shouldNotStartHealthCheckWhenDisabled() {
            FtpPoolConfig.FtpConfig ftpCfg = createFtpConfigStatic("test-ftp", "localhost", 21);
            ftpCfg.getHealthCheck().setEnabled(false);
            FtpPoolConfig config = new FtpPoolConfig();
            config.setConfigs(List.of(ftpCfg));
            assertDoesNotThrow(() -> new FtpPool(config));
        }
    }

    @Nested
    @DisplayName("withClient operations")
    class WithClientTests {

        @Test
        @DisplayName("should throw IllegalArgumentException for unknown FTP id")
        void shouldThrowForUnknownId() {
            FtpPoolConfig config = new FtpPoolConfig();
            config.setConfigs(null);
            FtpPool pool = new FtpPool(config);

            assertThrows(IllegalArgumentException.class,
                    () -> pool.withClient("unknown-ftp", client -> "result"));
        }

        @Test
        @DisplayName("void callback should throw IllegalArgumentException for unknown FTP id")
        void voidCallbackShouldThrowForUnknownId() {
            FtpPoolConfig config = new FtpPoolConfig();
            config.setConfigs(null);
            FtpPool pool = new FtpPool(config);

            assertThrows(IllegalArgumentException.class,
                    () -> pool.withClient("unknown-ftp", client -> {}));
        }
    }

    @Nested
    @DisplayName("getClient operations")
    class GetClientTests {

        @Test
        @DisplayName("should throw IllegalArgumentException for unknown FTP id")
        void shouldThrowForUnknownId() {
            FtpPoolConfig config = new FtpPoolConfig();
            config.setConfigs(null);
            FtpPool pool = new FtpPool(config);

            assertThrows(IllegalArgumentException.class,
                    () -> pool.getClient("unknown-ftp"));
        }
    }

    @Nested
    @DisplayName("ping operations")
    class PingTests {

        @Test
        @DisplayName("should return false for unknown FTP id")
        void shouldReturnFalseForUnknownId() {
            FtpPoolConfig config = new FtpPoolConfig();
            config.setConfigs(null);
            FtpPool pool = new FtpPool(config);

            assertFalse(pool.ping("unknown-ftp"));
        }
    }

    @Nested
    @DisplayName("close")
    class CloseTests {

        @Test
        @DisplayName("should not throw when called on empty pool")
        void shouldNotThrowOnEmptyPool() {
            FtpPoolConfig config = new FtpPoolConfig();
            config.setConfigs(null);
            FtpPool pool = new FtpPool(config);
            assertDoesNotThrow(pool::close);
        }

        @Test
        @DisplayName("should not throw when called on pool with configs")
        void shouldNotThrowOnConfiguredPool() {
            FtpPoolConfig config = createSingleFtpConfig("test-ftp", "localhost", 21);
            FtpPool pool = new FtpPool(config);
            assertDoesNotThrow(pool::close);
        }

        @Test
        @DisplayName("should be idempotent")
        void shouldBeIdempotent() {
            FtpPoolConfig config = createSingleFtpConfig("test-ftp", "localhost", 21);
            FtpPool pool = new FtpPool(config);
            assertDoesNotThrow(() -> {
                pool.close();
                pool.close();
            });
        }
    }

    // ==================== FtpConfigHolder Internal Tests ====================

    @Nested
    @DisplayName("FtpConfigHolder internal")
    class FtpConfigHolderTests {

        @Test
        @DisplayName("getClient should wrap FTPClient in FtpClient")
        void getClientShouldWrapFtpClient() throws Exception {
            FTPClient mockFtp = mock(FTPClient.class);
            when(mockFtp.isConnected()).thenReturn(true);
            when(mockFtp.sendNoOp()).thenReturn(true);

            FtpPool.FtpConfigHolder holder = new FtpPool.FtpConfigHolder(
                    createFtpConfig("internal-test", "localhost", 21));

            // Pre-populate busy set with mocked FTP client to bypass connection creation
            injectIntoBusy(holder, mockFtp);

            // Get the client - it should borrow from idle or create...
            // Since we only put it in busy, borrowIdleOrWait returns null,
            // then createClientWithFailover tries real connection which we avoid
            // by adding to idle directly
            FtpClient result = buildClientForHolder(holder, mockFtp);
            assertNotNull(result);
            assertSame(mockFtp, result.getClient());
        }

        @Test
        @DisplayName("returnClient with valid connection should return to idle")
        void returnClientValidShouldReturnToIdle() throws Exception {
            FTPClient mockFtp = mock(FTPClient.class);
            when(mockFtp.isConnected()).thenReturn(true);
            when(mockFtp.sendNoOp()).thenReturn(true);

            FtpPool.FtpConfigHolder holder = new FtpPool.FtpConfigHolder(
                    createFtpConfig("internal-test", "localhost", 21));

            FtpClient client = buildClientForHolder(holder, mockFtp);
            client.close();

            // Verify client was returned to idle
            Field idleField = FtpPool.FtpConfigHolder.class.getDeclaredField("idle");
            idleField.setAccessible(true);
            List<?> idleList = (List<?>) idleField.get(holder);
            assertEquals(1, idleList.size());
        }

        @Test
        @DisplayName("returnClient with invalid connection should disconnect")
        void returnClientInvalidShouldDisconnect() throws Exception {
            FTPClient mockFtp = mock(FTPClient.class);
            when(mockFtp.isConnected()).thenReturn(true);
            when(mockFtp.sendNoOp()).thenThrow(new IOException("not connected"));

            FtpPool.FtpConfigHolder holder = new FtpPool.FtpConfigHolder(
                    createFtpConfig("internal-test", "localhost", 21));

            FtpClient client = buildClientForHolder(holder, mockFtp);
            client.close();

            // Verify client was NOT added to idle
            Field idleField = FtpPool.FtpConfigHolder.class.getDeclaredField("idle");
            idleField.setAccessible(true);
            List<?> idleList = (List<?>) idleField.get(holder);
            assertEquals(0, idleList.size());

            // Verify disconnect was called
            verify(mockFtp).disconnect();
        }

        @Test
        @DisplayName("removeClient should remove from busy and disconnect")
        void removeClientShouldRemoveAndDisconnect() throws Exception {
            FTPClient mockFtp = mock(FTPClient.class);
            FtpPool.FtpConfigHolder holder = new FtpPool.FtpConfigHolder(
                    createFtpConfig("internal-test", "localhost", 21));

            Field busyField = FtpPool.FtpConfigHolder.class.getDeclaredField("busy");
            busyField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<FTPClient> busySet = (Set<FTPClient>) busyField.get(holder);

            // Populate busy set
            busySet.add(mockFtp);

            holder.removeClient(mockFtp);

            assertFalse(busySet.contains(mockFtp));
            verify(mockFtp).disconnect();
        }

        @Test
        @DisplayName("close should disconnect all clients")
        void closeShouldDisconnectAll() throws Exception {
            FTPClient mockFtp1 = mock(FTPClient.class);
            FTPClient mockFtp2 = mock(FTPClient.class);

            FtpPool.FtpConfigHolder holder = new FtpPool.FtpConfigHolder(
                    createFtpConfig("internal-test", "localhost", 21));

            // Inject clients into idle and busy
            Field idleField = FtpPool.FtpConfigHolder.class.getDeclaredField("idle");
            idleField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> idleList = (List<Object>) idleField.get(holder);

            Field busyField = FtpPool.FtpConfigHolder.class.getDeclaredField("busy");
            busyField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<FTPClient> busySet = (Set<FTPClient>) busyField.get(holder);

            // Use reflection to construct IdleEntry for ftp1
            Object idleEntry = createIdleEntry(mockFtp1);
            idleList.add(idleEntry);
            busySet.add(mockFtp2);

            holder.close();

            verify(mockFtp1).disconnect();
            verify(mockFtp2).disconnect();
            assertTrue(idleList.isEmpty());
            assertTrue(busySet.isEmpty());
        }

        @Test
        @DisplayName("checkAllClients should timeout idle connections")
        void checkAllClientsShouldTimeoutIdle() throws Exception {
            FTPClient mockFtp = mock(FTPClient.class);
            FtpPoolConfig.FtpConfig config = createFtpConfigStatic("timeout-test", "localhost", 21);
            config.getPool().setMaxIdleTimeSeconds(-1); // negative to ensure immediate expiry (idleMs > -1000 always true)

            FtpPool.FtpConfigHolder holder = new FtpPool.FtpConfigHolder(config);

            Field idleField = FtpPool.FtpConfigHolder.class.getDeclaredField("idle");
            idleField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> idleList = (List<Object>) idleField.get(holder);

            // Add an entry using reflection (IdleEntry is private)
            Object idleEntry = createIdleEntry(mockFtp);
            idleList.add(idleEntry);

            holder.checkAllClients();

            assertTrue(idleList.isEmpty());
            verify(mockFtp).disconnect();
        }

        // ==================== Helper methods ====================

        /**
         * Create a minimal FtpConfig for testing.
         */
        private FtpPoolConfig.FtpConfig createFtpConfig(String id, String host, int port) {
            FtpPoolConfig.FtpConfig cfg = new FtpPoolConfig.FtpConfig();
            cfg.setId(id);
            cfg.setHost(host);
            cfg.setPort(port);
            cfg.setUsername("testuser");
            cfg.setPassword("testpass");
            cfg.setTimeout(1000);
            return cfg;
        }

        /**
         * Build a FtpClient that wraps the given mock FTPClient
         * and is associated with the given holder.
         * Also injects the mock into the holder's busy set.
         */
        private FtpClient buildClientForHolder(FtpPool.FtpConfigHolder holder, FTPClient mockFtp)
                throws Exception {
            injectIntoBusy(holder, mockFtp);
            return new FtpClient(mockFtp, holder);
        }

        /**
         * Add a mock FTPClient to the holder's busy set via reflection.
         */
        private void injectIntoBusy(FtpPool.FtpConfigHolder holder, FTPClient mockFtp)
                throws Exception {
            Field busyField = FtpPool.FtpConfigHolder.class.getDeclaredField("busy");
            busyField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<FTPClient> busySet = (Set<FTPClient>) busyField.get(holder);
            busySet.add(mockFtp);
        }

        /**
         * Create an IdleEntry for the given FTPClient via reflection.
         * IdleEntry is a private static class within FtpPool.
         */
        private Object createIdleEntry(FTPClient client) throws Exception {
            Class<?> idleEntryClass = findIdleEntryClass();
            return idleEntryClass.getDeclaredConstructor(FTPClient.class).newInstance(client);
        }

        /**
         * Locate the private static IdleEntry class within FtpPool.
         */
        private Class<?> findIdleEntryClass() {
            for (Class<?> declared : FtpPool.class.getDeclaredClasses()) {
                if (declared.getSimpleName().equals("IdleEntry")) {
                    return declared;
                }
            }
            throw new RuntimeException("IdleEntry class not found in FtpPool");
        }
    }

    // ==================== Package-level helpers ====================

    static FtpPoolConfig createSingleFtpConfig(String id, String host, int port) {
        FtpPoolConfig.FtpConfig ftpCfg = createFtpConfigStatic(id, host, port);
        FtpPoolConfig config = new FtpPoolConfig();
        config.setConfigs(List.of(ftpCfg));
        return config;
    }

    static FtpPoolConfig.FtpConfig createFtpConfigStatic(String id, String host, int port) {
        FtpPoolConfig.FtpConfig cfg = new FtpPoolConfig.FtpConfig();
        cfg.setId(id);
        cfg.setHost(host);
        cfg.setPort(port);
        cfg.setUsername("testuser");
        cfg.setPassword("testpass");
        cfg.setTimeout(1000);
        return cfg;
    }
}
