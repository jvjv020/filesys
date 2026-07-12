package com.fmsy.model;

import com.fmsy.util.ColumnNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Result Tests")
class ResultTest {

    @Nested
    @DisplayName("Builder fluent API")
    class BuilderTests {

        @Test
        @DisplayName("should build result with all fields")
        void shouldBuildResultWithAllFields() {
            LocalDateTime startTime = LocalDateTime.of(2024, 1, 1, 12, 0, 0);
            LocalDate transmissionDate = LocalDate.of(2024, 1, 1);

            Result result = Result.builder()
                .commandId(1L)
                .categoryCode("CAT001")
                .controlCode("CTRL001")
                .ftpName("ftpServer")
                .filePath("/data/file.txt")
                .dbInfo("tableName")
                .dbName("DB_DEFAULT")
                .transmissionDate(transmissionDate)
                .status("Y")
                .startTime(startTime)
                .durationMs(1000L)
                .recordCount(100)
                .fileSize(2048L)
                .description("Success")
                .transferDirection(Result.DIRECTION_UPLOAD)
                .build();

            assertEquals(1L, result.getCommandId());
            assertEquals("CAT001", result.getCategoryCode());
            assertEquals("CTRL001", result.getControlCode());
            assertEquals("ftpServer", result.getFtpName());
            assertEquals("/data/file.txt", result.getFilePath());
            assertEquals("tableName", result.getDbInfo());
            assertEquals("DB_DEFAULT", result.getDbName());
            assertEquals(transmissionDate, result.getTransmissionDate());
            assertEquals("Y", result.getResult());
            assertEquals(startTime, result.getStartTime());
            assertEquals(1000L, result.getDurationMs());
            assertEquals(100, result.getRecordCount());
            assertEquals(2048L, result.getFileSize());
            assertEquals("Success", result.getDescription());
            assertEquals(Result.DIRECTION_UPLOAD, result.getTransferDirection());
        }

        @Test
        @DisplayName("should support partial builder pattern")
        void shouldSupportPartialBuilderPattern() {
            Result result = Result.builder()
                .commandId(2L)
                .categoryCode("CAT002")
                .status("E")
                .description("Error occurred")
                .build();

            assertEquals(2L, result.getCommandId());
            assertEquals("CAT002", result.getCategoryCode());
            assertEquals("E", result.getResult());
            assertEquals("Error occurred", result.getDescription());
            assertNull(result.getControlCode());
            assertNull(result.getFtpName());
        }

        @Test
        @DisplayName("builder should return this for chaining")
        void builderShouldReturnThisForChaining() {
            Result.Builder builder = new Result.Builder();
            assertSame(builder, builder.commandId(1L));
            assertSame(builder, builder.categoryCode("CAT"));
            assertSame(builder, builder.controlCode("CTRL"));
            assertSame(builder, builder.ftpName("ftp"));
            assertSame(builder, builder.filePath("/path"));
            assertSame(builder, builder.dbInfo("info"));
            assertSame(builder, builder.dbName("db"));
            assertSame(builder, builder.transmissionDate(LocalDate.now()));
            assertSame(builder, builder.status("Y"));
            assertSame(builder, builder.startTime(LocalDateTime.now()));
            assertSame(builder, builder.durationMs(100L));
            assertSame(builder, builder.recordCount(10));
            assertSame(builder, builder.fileSize(1000L));
            assertSame(builder, builder.description("desc"));
            assertSame(builder, builder.transferDirection(Result.DIRECTION_DOWNLOAD));
        }

        @Test
        @DisplayName("markStart should set startTimeMs via result instance")
        void markStartShouldSetStartTimeMs() {
            Result result = Result.builder()
                .markStart()
                .commandId(1L)
                .build();

            assertTrue(result.getStartTimeMs() > 0);
        }
    }

    @Nested
    @DisplayName("setOutcome")
    class SetOutcomeTests {

        @Test
        @DisplayName("should set recordCount result and description")
        void shouldSetAllFields() {
            Result result = new Result();
            result.setOutcome(500, "Y", "Completed successfully");

            assertEquals(500, result.getRecordCount());
            assertEquals("Y", result.getResult());
            assertEquals("Completed successfully", result.getDescription());
        }

        @Test
        @DisplayName("should handle null description")
        void shouldHandleNullDescription() {
            Result result = new Result();
            result.setOutcome(100, "N", null);

            assertEquals(100, result.getRecordCount());
            assertEquals("N", result.getResult());
            assertEquals("", result.getDescription());
        }
    }

    @Nested
    @DisplayName("markChildrenCreated")
    class MarkChildrenCreatedTests {

        @Test
        @DisplayName("should set suppressStatusUpdate and expectedChildren")
        void shouldSetFlagsAndExpectedChildren() {
            Result result = new Result();
            result.markChildrenCreated(5);

            assertEquals(ColumnNames.STATUS_PROCESSING, result.getResult());
            assertTrue(result.isSuppressStatusUpdate());
            assertEquals(5, result.getExpectedChildren());
            assertTrue(result.isNeedsChildMonitor());
        }
    }

    @Nested
    @DisplayName("markChildrenFailed")
    class MarkChildrenFailedTests {

        @Test
        @DisplayName("should set error status with reason")
        void shouldSetErrorWithReason() {
            Result result = new Result();
            result.markChildrenFailed("No buckets found");

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            assertEquals("No buckets found", result.getDescription());
        }

        @Test
        @DisplayName("should handle null reason")
        void shouldHandleNullReason() {
            Result result = new Result();
            result.markChildrenFailed(null);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            assertEquals("", result.getDescription());
        }
    }

    @Nested
    @DisplayName("failWith")
    class FailWithTests {

        @Test
        @DisplayName("should set error status from exception message")
        void shouldSetErrorFromExceptionMessage() {
            Result result = new Result();
            Exception e = new RuntimeException("Database connection failed");
            result.failWith(e);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            assertEquals("Database connection failed", result.getDescription());
        }

        @Test
        @DisplayName("should handle null exception")
        void shouldHandleNullException() {
            Result result = new Result();
            result.failWith(null);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            assertEquals("", result.getDescription());
        }
    }

    @Nested
    @DisplayName("markStart")
    class MarkStartTests {

        @Test
        @DisplayName("should record current time in startTimeMs")
        void shouldRecordCurrentTime() {
            Result result = new Result();
            long before = System.currentTimeMillis();
            result.markStart();
            long after = System.currentTimeMillis();

            assertTrue(result.getStartTimeMs() >= before);
            assertTrue(result.getStartTimeMs() <= after);
        }
    }

    @Nested
    @DisplayName("markEnd")
    class MarkEndTests {

        @Test
        @DisplayName("should populate fields from command and config")
        void shouldPopulateFieldsFromCommandAndConfig() {
            Command command = new Command();
            command.setId(10L);
            command.setCategoryCode("CAT001");
            command.setControlCode("CTRL001");
            LocalDateTime startTime = LocalDateTime.of(2024, 1, 1, 10, 0, 0);
            command.setStartTime(startTime);

            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");
            config.setFilePath("/data/out.csv");
            config.setTableName("targetTable");

            Result result = new Result();
            result.markStart();

            // Simulate some processing time
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}

            result.markEnd(command, config);

            assertEquals(10L, result.getCommandId());
            assertEquals("CAT001", result.getCategoryCode());
            assertEquals("CTRL001", result.getControlCode());
            assertEquals("ftp1", result.getFtpName());
            assertEquals("/data/out.csv", result.getFilePath());
            assertEquals("targetTable", result.getDbInfo());
            assertEquals(LocalDate.now(), result.getTransmissionDate());
            assertEquals(startTime, result.getStartTime());
            assertTrue(result.getDurationMs() > 0);
            assertEquals(0L, result.getFileSize());
        }

        @Test
        @DisplayName("should default to ERROR when result is null")
        void shouldDefaultToErrorWhenResultIsNull() {
            Command command = new Command();
            command.setId(1L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp");
            config.setFilePath("/path");
            config.setTableName("table");

            Result result = new Result();
            result.markEnd(command, config);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }
    }

    @Nested
    @DisplayName("transient fields")
    class TransientFieldsTests {

        @Test
        @DisplayName("suppressStatusUpdate should be false by default")
        void suppressStatusUpdateShouldBeFalseByDefault() {
            Result result = new Result();
            // Use reflection to check the transient field since setter is private
            assertFalse(extractSuppressStatusUpdate(result));
        }

        private boolean extractSuppressStatusUpdate(Result result) {
            try {
                var field = Result.class.getDeclaredField("suppressStatusUpdate");
                field.setAccessible(true);
                return field.getBoolean(result);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
