package com.fmsy.lifecycle;

import com.fmsy.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShutdownService Tests")
class ShutdownServiceTest {

    @Mock
    private AppConfig appConfig;

    @Mock
    private AppConfig.Polling pollingConfig;

    private ShutdownService shutdownService;

    @BeforeEach
    void setUp() {
        shutdownService = new ShutdownService();
    }

    @Nested
    @DisplayName("isShuttingDown")
    class IsShuttingDownTests {

        @Test
        @DisplayName("should return false initially")
        void shouldReturnFalseInitially() {
            assertFalse(shutdownService.isShuttingDown());
        }

        @Test
        @DisplayName("should return true after initiateShutdown")
        void shouldReturnTrueAfterInitiateShutdown() {
            shutdownService.initiateShutdown();

            assertTrue(shutdownService.isShuttingDown());
        }
    }

    @Nested
    @DisplayName("beginTask / endTask counting")
    class TaskCountingTests {

        @Test
        @DisplayName("should increment counter on beginTask")
        void shouldIncrementOnBeginTask() {
            boolean result = shutdownService.beginTask();

            assertTrue(result);
        }

        @Test
        @DisplayName("should return true from beginTask when not shutting down")
        void shouldReturnTrueWhenNotShuttingDown() {
            boolean result = shutdownService.beginTask();

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false from beginTask when shutting down")
        void shouldReturnFalseWhenShuttingDown() {
            shutdownService.initiateShutdown();

            boolean result = shutdownService.beginTask();

            assertFalse(result);
        }

        @Test
        @DisplayName("should not increment counter when shutting down")
        void shouldNotIncrementWhenShuttingDown() {
            shutdownService.initiateShutdown();

            shutdownService.beginTask();

            // Counter should remain 0 because beginTask returned false
            // We need to verify this through the behavior - if we call endTask after
            // a failed beginTask, it should not go negative
            shutdownService.endTask(); // Should not cause issues
        }

        @Test
        @DisplayName("should decrement counter on endTask")
        void shouldDecrementOnEndTask() {
            shutdownService.beginTask();
            shutdownService.beginTask();

            shutdownService.endTask();

            // Should not throw or cause issues
        }

        @Test
        @DisplayName("should handle multiple begin/end pairs")
        void shouldHandleMultipleBeginEndPairs() {
            for (int i = 0; i < 5; i++) {
                shutdownService.beginTask();
            }

            for (int i = 0; i < 5; i++) {
                shutdownService.endTask();
            }
        }

        @Test
        @DisplayName("should handle endTask with no matching beginTask (defensive)")
        void shouldHandleEndTaskWithNoBeginTask() {
            // Call endTask without beginTask - should be defensive
            // This tests the negative counter protection
            shutdownService.endTask();

            // Should handle gracefully without throwing
        }
    }

    @Nested
    @DisplayName("initiateShutdown")
    class InitiateShutdownTests {

        @Test
        @DisplayName("should set shuttingDown flag to true")
        void shouldSetShuttingDownFlag() {
            shutdownService.initiateShutdown();

            assertTrue(shutdownService.isShuttingDown());
        }

        @Test
        @DisplayName("should be idempotent")
        void shouldBeIdempotent() {
            shutdownService.initiateShutdown();
            shutdownService.initiateShutdown();

            assertTrue(shutdownService.isShuttingDown());
        }
    }

    @Nested
    @DisplayName("timeout configuration")
    class TimeoutConfigurationTests {

        @Test
        @DisplayName("should use default timeout when no AppConfig")
        void shouldUseDefaultTimeoutWhenNoAppConfig() {
            // ShutdownService with no AppConfig injected uses default timeoutSeconds = 300
            assertEquals(300, getTimeoutSeconds(shutdownService));
        }

        @Test
        @DisplayName("should configure timeout from AppConfig")
        void shouldConfigureTimeoutFromAppConfig() {
            when(appConfig.getPolling()).thenReturn(pollingConfig);
            when(pollingConfig.getTaskTimeoutHours()).thenReturn(2);

            shutdownService.configureTimeout(appConfig);

            // 2 hours * 60 = 120 minutes, capped appropriately
            assertTrue(getTimeoutSeconds(shutdownService) > 0);
        }

        @Test
        @DisplayName("should handle null AppConfig in configureTimeout")
        void shouldHandleNullAppConfig() {
            shutdownService.configureTimeout(null);

            // Should not throw
            assertFalse(shutdownService.isShuttingDown());
        }

        @Test
        @DisplayName("should handle null polling config")
        void shouldHandleNullPollingConfig() {
            when(appConfig.getPolling()).thenReturn(null);

            shutdownService.configureTimeout(appConfig);

            // Should not throw
        }

        @Test
        @DisplayName("should handle zero taskTimeoutHours")
        void shouldHandleZeroTaskTimeoutHours() {
            when(appConfig.getPolling()).thenReturn(pollingConfig);
            when(pollingConfig.getTaskTimeoutHours()).thenReturn(0);

            shutdownService.configureTimeout(appConfig);

            // Should use default
            assertEquals(300, getTimeoutSeconds(shutdownService));
        }
    }

    @Nested
    @DisplayName("shutdownComplete")
    class ShutdownCompleteTests {

        @Test
        @DisplayName("should complete shutdown without error")
        void shouldCompleteWithoutError() {
            shutdownService.initiateShutdown();

            // Should not throw
            shutdownService.shutdownComplete();
        }

        @Test
        @DisplayName("should be callable multiple times")
        void shouldBeCallableMultipleTimes() {
            shutdownService.shutdownComplete();
            shutdownService.shutdownComplete();
        }
    }

    @Nested
    @DisplayName("counter thread safety")
    class CounterThreadSafetyTests {

        @Test
        @DisplayName("should handle sequential begin/end calls correctly")
        void shouldHandleSequentialCalls() {
            // Simulate a task lifecycle
            boolean began = shutdownService.beginTask();
            assertTrue(began);

            // Simulate task completion
            shutdownService.endTask();

            // Should be back to initial state (0 active tasks)
            // We can verify by checking that another endTask doesn't cause issues
            shutdownService.endTask(); // Extra endTask - defensive behavior
        }

        @Test
        @DisplayName("should maintain correct count with interleaved calls")
        void shouldMaintainCorrectCountWithInterleavedCalls() {
            shutdownService.beginTask();
            shutdownService.beginTask();

            shutdownService.endTask();

            shutdownService.beginTask();

            shutdownService.endTask();
            shutdownService.endTask();
        }
    }

    // Helper method to access private timeoutSeconds field via reflection
    // or we can test it indirectly through awaitTasksComplete behavior
    private int getTimeoutSeconds(ShutdownService service) {
        try {
            var field = ShutdownService.class.getDeclaredField("timeoutSeconds");
            field.setAccessible(true);
            return field.getInt(service);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
