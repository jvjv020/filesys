package com.fmsy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ColumnNames Tests")
class ColumnNamesTest {

    @Nested
    @DisplayName("command table constants")
    class CommandTableConstantsTests {

        @Test
        @DisplayName("ID should be non-null and non-empty")
        void idShouldBeNonNullAndNonEmpty() {
            assertNotNull(ColumnNames.ID);
            assertFalse(ColumnNames.ID.isEmpty());
        }

        @Test
        @DisplayName("CATEGORY_CODE should be non-null and non-empty")
        void categoryCodeShouldBeNonNullAndNonEmpty() {
            assertNotNull(ColumnNames.CATEGORY_CODE);
            assertFalse(ColumnNames.CATEGORY_CODE.isEmpty());
        }

        @Test
        @DisplayName("CONTROL_CODE should be non-null and non-empty")
        void controlCodeShouldBeNonNullAndNonEmpty() {
            assertNotNull(ColumnNames.CONTROL_CODE);
            assertFalse(ColumnNames.CONTROL_CODE.isEmpty());
        }

        @Test
        @DisplayName("COMMAND_TYPE should be non-null and non-empty")
        void commandTypeShouldBeNonNullAndNonEmpty() {
            assertNotNull(ColumnNames.COMMAND_TYPE);
            assertFalse(ColumnNames.COMMAND_TYPE.isEmpty());
        }

        @Test
        @DisplayName("PROCESS_STATUS should be non-null and non-empty")
        void processStatusShouldBeNonNullAndNonEmpty() {
            assertNotNull(ColumnNames.PROCESS_STATUS);
            assertFalse(ColumnNames.PROCESS_STATUS.isEmpty());
        }

        @Test
        @DisplayName("PROCESSING_NODE should be non-null and non-empty")
        void processingNodeShouldBeNonNullAndNonEmpty() {
            assertNotNull(ColumnNames.PROCESSING_NODE);
            assertFalse(ColumnNames.PROCESSING_NODE.isEmpty());
        }
    }

    @Nested
    @DisplayName("status constants")
    class StatusConstantsTests {

        @Test
        @DisplayName("STATUS_EMPTY should be empty string")
        void statusEmptyShouldBeEmptyString() {
            assertEquals("", ColumnNames.STATUS_EMPTY);
        }

        @Test
        @DisplayName("STATUS_PROCESSING should be P")
        void statusProcessingShouldBeP() {
            assertEquals("P", ColumnNames.STATUS_PROCESSING);
        }

        @Test
        @DisplayName("STATUS_SUCCESS should be Y")
        void statusSuccessShouldBeY() {
            assertEquals("Y", ColumnNames.STATUS_SUCCESS);
        }

        @Test
        @DisplayName("STATUS_SKIPPED should be N")
        void statusSkippedShouldBeN() {
            assertEquals("N", ColumnNames.STATUS_SKIPPED);
        }

        @Test
        @DisplayName("STATUS_ERROR should be E")
        void statusErrorShouldBeE() {
            assertEquals("E", ColumnNames.STATUS_ERROR);
        }

        @Test
        @DisplayName("STATUS_VALID should be valid")
        void statusValidShouldBeValid() {
            assertEquals("有效", ColumnNames.STATUS_VALID);
        }
    }

    @Nested
    @DisplayName("result table constants")
    class ResultTableConstantsTests {

        @Test
        @DisplayName("RESULT should be non-null and non-empty")
        void resultShouldBeNonNullAndNonEmpty() {
            assertNotNull(ColumnNames.RESULT);
            assertFalse(ColumnNames.RESULT.isEmpty());
        }

        @Test
        @DisplayName("RECORD_COUNT should be non-null and non-empty")
        void recordCountShouldBeNonNullAndNonEmpty() {
            assertNotNull(ColumnNames.RECORD_COUNT);
            assertFalse(ColumnNames.RECORD_COUNT.isEmpty());
        }

        @Test
        @DisplayName("FILE_SIZE should be non-null and non-empty")
        void fileSizeShouldBeNonNullAndNonEmpty() {
            assertNotNull(ColumnNames.FILE_SIZE);
            assertFalse(ColumnNames.FILE_SIZE.isEmpty());
        }
    }

    @Nested
    @DisplayName("default constants")
    class DefaultConstantsTests {

        @Test
        @DisplayName("DEFAULT_DB should be DB_DEFAULT")
        void defaultDbShouldBeDbDefault() {
            assertEquals("DB_DEFAULT", ColumnNames.DEFAULT_DB);
        }
    }
}
