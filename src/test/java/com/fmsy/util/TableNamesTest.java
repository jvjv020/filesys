package com.fmsy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TableNames Tests")
class TableNamesTest {

    @Nested
    @DisplayName("table name constants")
    class TableNameConstantsTests {

        @Test
        @DisplayName("COMMAND_TABLE should be non-null and non-empty")
        void commandTableShouldBeNonNullAndNonEmpty() {
            assertNotNull(TableNames.COMMAND_TABLE);
            assertFalse(TableNames.COMMAND_TABLE.isEmpty());
        }

        @Test
        @DisplayName("DETAIL_TABLE should be non-null and non-empty")
        void detailTableShouldBeNonNullAndNonEmpty() {
            assertNotNull(TableNames.DETAIL_TABLE);
            assertFalse(TableNames.DETAIL_TABLE.isEmpty());
        }

        @Test
        @DisplayName("RESULT_TABLE should be non-null and non-empty")
        void resultTableShouldBeNonNullAndNonEmpty() {
            assertNotNull(TableNames.RESULT_TABLE);
            assertFalse(TableNames.RESULT_TABLE.isEmpty());
        }

        @Test
        @DisplayName("TRANSFER_CONFIG_TABLE should be non-null and non-empty")
        void transferConfigTableShouldBeNonNullAndNonEmpty() {
            assertNotNull(TableNames.TRANSFER_CONFIG_TABLE);
            assertFalse(TableNames.TRANSFER_CONFIG_TABLE.isEmpty());
        }

        @Test
        @DisplayName("table names should be distinct")
        void tableNamesShouldBeDistinct() {
            assertNotEquals(TableNames.COMMAND_TABLE, TableNames.DETAIL_TABLE);
            assertNotEquals(TableNames.COMMAND_TABLE, TableNames.RESULT_TABLE);
            assertNotEquals(TableNames.COMMAND_TABLE, TableNames.TRANSFER_CONFIG_TABLE);
            assertNotEquals(TableNames.DETAIL_TABLE, TableNames.RESULT_TABLE);
            assertNotEquals(TableNames.DETAIL_TABLE, TableNames.TRANSFER_CONFIG_TABLE);
            assertNotEquals(TableNames.RESULT_TABLE, TableNames.TRANSFER_CONFIG_TABLE);
        }

        @Test
        @DisplayName("all table names should contain expected Chinese characters")
        void allTableNamesShouldContainExpectedCharacters() {
            assertTrue(TableNames.COMMAND_TABLE.contains("指令"));
            assertTrue(TableNames.DETAIL_TABLE.contains("明细"));
            assertTrue(TableNames.RESULT_TABLE.contains("结果"));
            assertTrue(TableNames.TRANSFER_CONFIG_TABLE.contains("传输配置"));
        }
    }
}
