package com.fmsy.model;

import com.fmsy.enums.CommandType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Command Tests")
class CommandTest {

    @Nested
    @DisplayName("markStartTimeIfAbsent")
    class MarkStartTimeIfAbsentTests {

        @Test
        @DisplayName("should set startTime when null")
        void shouldSetStartTimeWhenNull() {
            Command command = new Command();
            assertNull(command.getStartTime());

            command.markStartTimeIfAbsent();

            assertNotNull(command.getStartTime());
            assertEquals(LocalDateTime.now().getClass(), command.getStartTime().getClass());
        }

        @Test
        @DisplayName("should be idempotent - not overwrite existing startTime")
        void shouldBeIdempotent() {
            Command command = new Command();
            LocalDateTime originalTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
            command.setStartTime(originalTime);

            command.markStartTimeIfAbsent();

            assertEquals(originalTime, command.getStartTime());
        }

        @Test
        @DisplayName("should handle multiple calls with same result")
        void multipleCallsShouldHaveSameResult() {
            Command command = new Command();
            command.markStartTimeIfAbsent();
            LocalDateTime firstCallTime = command.getStartTime();

            // Wait a tiny bit to ensure time difference would be visible
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}

            command.markStartTimeIfAbsent();

            assertEquals(firstCallTime, command.getStartTime());
        }
    }

    @Nested
    @DisplayName("field getter/setter")
    class FieldTests {

        @Test
        @DisplayName("should store and retrieve id")
        void shouldStoreAndRetrieveId() {
            Command command = new Command();
            command.setId(123L);
            assertEquals(123L, command.getId());
        }

        @Test
        @DisplayName("should store and retrieve categoryCode")
        void shouldStoreAndRetrieveCategoryCode() {
            Command command = new Command();
            command.setCategoryCode("CAT001");
            assertEquals("CAT001", command.getCategoryCode());
        }

        @Test
        @DisplayName("should store and retrieve controlCode")
        void shouldStoreAndRetrieveControlCode() {
            Command command = new Command();
            command.setControlCode("CTRL001");
            assertEquals("CTRL001", command.getControlCode());
        }

        @Test
        @DisplayName("should store and retrieve commandType")
        void shouldStoreAndRetrieveCommandType() {
            Command command = new Command();
            command.setCommandType(CommandType.BATCH);
            assertEquals(CommandType.BATCH, command.getCommandType());
        }

        @Test
        @DisplayName("should store and retrieve auditCount")
        void shouldStoreAndRetrieveAuditCount() {
            Command command = new Command();
            command.setAuditCount(1000);
            assertEquals(1000, command.getAuditCount());
        }

        @Test
        @DisplayName("should store and retrieve extraInfo")
        void shouldStoreAndRetrieveExtraInfo() {
            Command command = new Command();
            command.setExtraInfo("mainId|baseFilePath");
            assertEquals("mainId|baseFilePath", command.getExtraInfo());
        }

        @Test
        @DisplayName("should store and retrieve tempConfig")
        void shouldStoreAndRetrieveTempConfig() {
            Command command = new Command();
            command.setTempConfig("{\"key\":\"value\"}");
            assertEquals("{\"key\":\"value\"}", command.getTempConfig());
        }

        @Test
        @DisplayName("should store and retrieve status")
        void shouldStoreAndRetrieveStatus() {
            Command command = new Command();
            command.setStatus("P");
            assertEquals("P", command.getStatus());
        }
    }
}
