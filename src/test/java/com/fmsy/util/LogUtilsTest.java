package com.fmsy.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LogUtils Tests")
class LogUtilsTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("setTaskId")
    class SetTaskIdTests {

        @Test
        @DisplayName("should set taskId in MDC")
        void shouldSetTaskId() {
            LogUtils.setTaskId(12345L);

            assertEquals("12345", MDC.get("taskId"));
        }

        @Test
        @DisplayName("should not set taskId when null")
        void shouldNotSetNullTaskId() {
            LogUtils.setTaskId(null);

            assertNull(MDC.get("taskId"));
        }

        @Test
        @DisplayName("should overwrite existing taskId")
        void shouldOverwriteExistingTaskId() {
            LogUtils.setTaskId(100L);
            LogUtils.setTaskId(200L);

            assertEquals("200", MDC.get("taskId"));
        }

        @Test
        @DisplayName("should handle zero task ID")
        void shouldHandleZeroTaskId() {
            LogUtils.setTaskId(0L);

            assertEquals("0", MDC.get("taskId"));
        }

        @Test
        @DisplayName("should handle negative task ID")
        void shouldHandleNegativeTaskId() {
            LogUtils.setTaskId(-1L);

            assertEquals("-1", MDC.get("taskId"));
        }

        @Test
        @DisplayName("should handle Long.MAX_VALUE")
        void shouldHandleMaxLong() {
            LogUtils.setTaskId(Long.MAX_VALUE);

            assertEquals(String.valueOf(Long.MAX_VALUE), MDC.get("taskId"));
        }
    }

    @Nested
    @DisplayName("setNodeId")
    class SetNodeIdTests {

        @Test
        @DisplayName("should set nodeId in MDC")
        void shouldSetNodeId() {
            LogUtils.setNodeId("node-1");

            assertEquals("node-1", MDC.get("nodeId"));
        }

        @Test
        @DisplayName("should not set nodeId when null")
        void shouldNotSetNullNodeId() {
            LogUtils.setNodeId(null);

            assertNull(MDC.get("nodeId"));
        }

        @Test
        @DisplayName("should overwrite existing nodeId")
        void shouldOverwriteExistingNodeId() {
            LogUtils.setNodeId("node-old");
            LogUtils.setNodeId("node-new");

            assertEquals("node-new", MDC.get("nodeId"));
        }

        @Test
        @DisplayName("should handle empty string nodeId")
        void shouldHandleEmptyNodeId() {
            LogUtils.setNodeId("");

            // Empty string is not null, so it will be set
            assertEquals("", MDC.get("nodeId"));
        }

        @Test
        @DisplayName("should handle complex node IDs with special characters")
        void shouldHandleComplexNodeIds() {
            LogUtils.setNodeId("node-192.168.1.1_8080");

            assertEquals("node-192.168.1.1_8080", MDC.get("nodeId"));
        }
    }

    @Nested
    @DisplayName("MDC isolation between calls")
    class MdcIsolationTests {

        @Test
        @DisplayName("should set both taskId and nodeId independently")
        void shouldSetBothIndependently() {
            LogUtils.setTaskId(42L);
            LogUtils.setNodeId("primary");

            assertEquals("42", MDC.get("taskId"));
            assertEquals("primary", MDC.get("nodeId"));
        }

        @Test
        @DisplayName("should preserve unrelated MDC keys")
        void shouldPreserveUnrelatedKeys() {
            MDC.put("someOtherKey", "someValue");
            LogUtils.setTaskId(1L);

            assertEquals("someValue", MDC.get("someOtherKey"));
            assertEquals("1", MDC.get("taskId"));
        }

        @Test
        @DisplayName("should be clearable via MDC.clear")
        void shouldBeClearable() {
            LogUtils.setTaskId(999L);
            LogUtils.setNodeId("node-x");

            MDC.clear();

            assertNull(MDC.get("taskId"));
            assertNull(MDC.get("nodeId"));
        }

        @Test
        @DisplayName("should allow setting taskId after nodeId")
        void shouldAllowSettingAfter() {
            LogUtils.setNodeId("node-before");
            LogUtils.setTaskId(777L);

            assertEquals("777", MDC.get("taskId"));
            assertEquals("node-before", MDC.get("nodeId"));
        }
    }
}
