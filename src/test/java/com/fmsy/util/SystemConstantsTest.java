package com.fmsy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SystemConstants Tests")
class SystemConstantsTest {

    @Nested
    @DisplayName("constant values")
    class ConstantValuesTests {

        @Test
        @DisplayName("DEFAULT_BATCH_SIZE should be 1000")
        void defaultBatchSizeShouldBe1000() {
            assertEquals(1000, SystemConstants.DEFAULT_BATCH_SIZE);
        }

        @Test
        @DisplayName("MAX_RETRIES should be 1")
        void maxRetriesShouldBe1() {
            assertEquals(1, SystemConstants.MAX_RETRIES);
        }

        @Test
        @DisplayName("MONITOR_MAX_ITERATIONS should be 60")
        void monitorMaxIterationsShouldBe60() {
            assertEquals(60, SystemConstants.MONITOR_MAX_ITERATIONS);
        }

        @Test
        @DisplayName("MONITOR_INTERVAL_MS should be 5000")
        void monitorIntervalMsShouldBe5000() {
            assertEquals(5000, SystemConstants.MONITOR_INTERVAL_MS);
        }

        @Test
        @DisplayName("constants should be positive")
        void constantsShouldBePositive() {
            assertTrue(SystemConstants.DEFAULT_BATCH_SIZE > 0);
            assertTrue(SystemConstants.MAX_RETRIES >= 0);
            assertTrue(SystemConstants.MONITOR_MAX_ITERATIONS > 0);
            assertTrue(SystemConstants.MONITOR_INTERVAL_MS > 0);
        }

        @Test
        @DisplayName("DEFAULT_BATCH_SIZE should be greater than MAX_RETRIES")
        void batchSizeShouldBeGreaterThanRetries() {
            assertTrue(SystemConstants.DEFAULT_BATCH_SIZE > SystemConstants.MAX_RETRIES);
        }
    }
}
