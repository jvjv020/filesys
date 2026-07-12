package com.fmsy.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SqlBuilder Tests")
class SqlBuilderTest {

    @Nested
    @DisplayName("buildBatchInsert")
    class BuildBatchInsertTests {

        @Test
        @DisplayName("should build single-row insert")
        void shouldBuildSingleRowInsert() {
            String sql = SqlBuilder.buildBatchInsert("my_table",
                    Arrays.asList("col1", "col2", "col3"), 1);
            assertEquals("INSERT INTO my_table (col1, col2, col3) VALUES (?, ?, ?)", sql);
        }

        @Test
        @DisplayName("should build multi-row insert")
        void shouldBuildMultiRowInsert() {
            String sql = SqlBuilder.buildBatchInsert("my_table",
                    Arrays.asList("col1", "col2"), 3);
            assertEquals(
                    "INSERT INTO my_table (col1, col2) VALUES (?, ?), (?, ?), (?, ?)",
                    sql);
        }

        @Test
        @DisplayName("should reject invalid table name with SQL injection attempt")
        void shouldRejectInvalidTableName() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildBatchInsert("users; DROP TABLE users; --",
                            List.of("id"), 1));
        }

        @Test
        @DisplayName("should reject batch size exceeding maximum")
        void shouldRejectBatchSizeExceedingMax() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildBatchInsert("my_table", List.of("id"), 2001));
        }

        @Test
        @DisplayName("should reject null table name")
        void shouldRejectNullTableName() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildBatchInsert(null, List.of("id"), 1));
        }

        @Test
        @DisplayName("should reject SQL injection in field names")
        void shouldRejectInjectionInFieldNames() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildBatchInsert("my_table",
                            List.of("id; SELECT * FROM users; --"), 1));
        }

        @Test
        @DisplayName("should accept table name with underscore")
        void shouldAcceptTableWithUnderscore() {
            String sql = SqlBuilder.buildBatchInsert("order_items",
                    List.of("product_id", "quantity"), 1);
            assertTrue(sql.startsWith("INSERT INTO order_items"));
            assertTrue(sql.contains("product_id"));
            assertTrue(sql.contains("quantity"));
        }
    }

    @Nested
    @DisplayName("buildSelectParametric")
    class BuildSelectParametricTests {

        @Test
        @DisplayName("should build simple select with *")
        void shouldBuildSimpleSelectStar() {
            SqlStatement stmt = SqlBuilder.buildSelectParametric(
                    "users", Collections.emptyList(), false,
                    null, null, null, null, null);
            assertEquals("SELECT * FROM users", stmt.getSql());
            assertTrue(stmt.getParams().isEmpty());
        }

        @Test
        @DisplayName("should build select with field list")
        void shouldBuildSelectWithFields() {
            SqlStatement stmt = SqlBuilder.buildSelectParametric(
                    "users", Arrays.asList("id", "name"), false,
                    null, null, null, null, null);
            assertEquals("SELECT id, name FROM users", stmt.getSql());
        }

        @Test
        @DisplayName("should build select with WHERE conditions")
        void shouldBuildSelectWithConditions() {
            SqlStatement stmt = SqlBuilder.buildSelectParametric(
                    "users", Arrays.asList("id", "name"), false,
                    Arrays.asList("status = ?", "age > ?"),
                    Arrays.asList("ACTIVE", 18),
                    null, null, null);
            assertEquals("SELECT id, name FROM users WHERE status = ? AND age > ?",
                    stmt.getSql());
            assertEquals(2, stmt.getParams().size());
            assertEquals("ACTIVE", stmt.getParams().get(0));
            assertEquals(18, stmt.getParams().get(1));
        }

        @Test
        @DisplayName("should build select with DISTINCT")
        void shouldBuildSelectWithDistinct() {
            SqlStatement stmt = SqlBuilder.buildSelectParametric(
                    "users", List.of("category"), true,
                    null, null, null, null, null);
            assertEquals("SELECT DISTINCT category FROM users", stmt.getSql());
        }

        @Test
        @DisplayName("should build select with ORDER BY")
        void shouldBuildSelectWithOrderBy() {
            SqlStatement stmt = SqlBuilder.buildSelectParametric(
                    "users", List.of("id", "name"), false,
                    null, null,
                    Arrays.asList("name", "id"), null, null);
            assertEquals("SELECT id, name FROM users ORDER BY name, id", stmt.getSql());
        }

        @Test
        @DisplayName("should build select with LIMIT and OFFSET")
        void shouldBuildSelectWithLimitAndOffset() {
            SqlStatement stmt = SqlBuilder.buildSelectParametric(
                    "users", List.of("id"), false,
                    null, null, null, 10, 20);
            assertEquals("SELECT id FROM users LIMIT 10 OFFSET 20", stmt.getSql());
        }

        @Test
        @DisplayName("should reject SQL injection in table name")
        void shouldRejectInjectionInTableName() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildSelectParametric(
                            "users; DELETE FROM users; --",
                            List.of("id"), false, null, null, null, null, null));
        }

        @Test
        @DisplayName("should reject SQL injection in ORDER BY field")
        void shouldRejectInjectionInOrderBy() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildSelectParametric(
                            "users", List.of("id"), false,
                            null, null,
                            List.of("id; DROP TABLE users; --"), null, null));
        }

        @Test
        @DisplayName("should reject SQL injection in field list")
        void shouldRejectInjectionInFieldList() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildSelectParametric(
                            "users",
                            List.of("id", "password AS admin_password FROM users; --"),
                            false, null, null, null, null, null));
        }

        @Test
        @DisplayName("should handle all optional parameters together")
        void shouldHandleAllOptionalParams() {
            SqlStatement stmt = SqlBuilder.buildSelectParametric(
                    "orders", Arrays.asList("id", "total"), true,
                    Arrays.asList("status = ?"),
                    List.of("SHIPPED"),
                    List.of("created_at"),
                    5, 0);
            assertEquals(
                    "SELECT DISTINCT id, total FROM orders WHERE status = ? ORDER BY created_at LIMIT 5 OFFSET 0",
                    stmt.getSql());
            assertEquals(1, stmt.getParams().size());
            assertEquals("SHIPPED", stmt.getParams().get(0));
        }

        @Test
        @DisplayName("should handle empty conditions without WHERE clause")
        void shouldHandleEmptyConditions() {
            SqlStatement stmt = SqlBuilder.buildSelectParametric(
                    "users", List.of("id"), false,
                    Collections.emptyList(), Collections.emptyList(),
                    null, null, null);
            assertEquals("SELECT id FROM users", stmt.getSql());
        }

        @Test
        @DisplayName("should build select with LIMIT only")
        void shouldBuildSelectWithLimitOnly() {
            SqlStatement stmt = SqlBuilder.buildSelectParametric(
                    "users", List.of("id"), false,
                    null, null, null, 100, null);
            assertEquals("SELECT id FROM users LIMIT 100", stmt.getSql());
        }
    }

    @Nested
    @DisplayName("buildDeleteAll")
    class BuildDeleteAllTests {

        @Test
        @DisplayName("should build delete all statement")
        void shouldBuildDeleteAll() {
            String sql = SqlBuilder.buildDeleteAll("staging_data");
            assertEquals("DELETE FROM staging_data", sql);
        }

        @Test
        @DisplayName("should reject SQL injection in table name")
        void shouldRejectInjectionInTableName() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildDeleteAll("staging_data; DROP TABLE users; --"));
        }

        @Test
        @DisplayName("should reject null table name")
        void shouldRejectNullTableName() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildDeleteAll(null));
        }

        @Test
        @DisplayName("should reject empty table name")
        void shouldRejectEmptyTableName() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildDeleteAll(""));
        }
    }

    @Nested
    @DisplayName("buildCountParametric")
    class BuildCountParametricTests {

        @Test
        @DisplayName("should build count without conditions")
        void shouldBuildCountWithoutConditions() {
            SqlStatement stmt = SqlBuilder.buildCountParametric(
                    "users", null, null);
            assertEquals("SELECT COUNT(*) FROM users", stmt.getSql());
            assertTrue(stmt.getParams().isEmpty());
        }

        @Test
        @DisplayName("should build count with conditions")
        void shouldBuildCountWithConditions() {
            SqlStatement stmt = SqlBuilder.buildCountParametric(
                    "orders",
                    Arrays.asList("status = ?", "created_at > ?"),
                    Arrays.asList("PAID", "2024-01-01"));
            assertEquals("SELECT COUNT(*) FROM orders WHERE status = ? AND created_at > ?",
                    stmt.getSql());
            assertEquals(2, stmt.getParams().size());
            assertEquals("PAID", stmt.getParams().get(0));
            assertEquals("2024-01-01", stmt.getParams().get(1));
        }

        @Test
        @DisplayName("should reject SQL injection in table name")
        void shouldRejectInjectionInTableName() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildCountParametric(
                            "users UNION SELECT * FROM passwords", null, null));
        }

        @Test
        @DisplayName("should handle empty conditions gracefully")
        void shouldHandleEmptyConditions() {
            SqlStatement stmt = SqlBuilder.buildCountParametric(
                    "users", Collections.emptyList(), Collections.emptyList());
            assertEquals("SELECT COUNT(*) FROM users", stmt.getSql());
        }

        @Test
        @DisplayName("should handle condition without matching param")
        void shouldHandleConditionWithoutParam() {
            SqlStatement stmt = SqlBuilder.buildCountParametric(
                    "users", List.of("1 = 1"), Collections.emptyList());
            assertEquals("SELECT COUNT(*) FROM users WHERE 1 = 1", stmt.getSql());
            assertTrue(stmt.getParams().isEmpty());
        }
    }

    @Nested
    @DisplayName("identifier validation")
    class IdentifierValidationTests {

        @Test
        @DisplayName("should accept valid identifiers")
        void shouldAcceptValidIdentifiers() {
            assertDoesNotThrow(() -> SqlBuilder.buildBatchInsert("valid_table", List.of("col"), 1));
        }

        @Test
        @DisplayName("should accept identifiers with numbers")
        void shouldAcceptIdentifiersWithNumbers() {
            assertDoesNotThrow(() -> SqlBuilder.buildBatchInsert("table_123", List.of("col_456"), 1));
        }

        @Test
        @DisplayName("should reject identifiers starting with number")
        void shouldRejectIdentifierStartingWithNumber() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildBatchInsert("1table", List.of("col"), 1));
        }

        @Test
        @DisplayName("should reject SQL keyword injection")
        void shouldRejectSqlKeywordInjection() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildBatchInsert("users WHERE 1=1", List.of("col"), 1));
        }

        @Test
        @DisplayName("should reject semicolon injection")
        void shouldRejectSemicolonInjection() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildBatchInsert("users; DELETE FROM", List.of("col"), 1));
        }

        @Test
        @DisplayName("should reject whitespace in identifier")
        void shouldRejectWhitespaceInIdentifier() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlBuilder.buildBatchInsert("my table", List.of("col"), 1));
        }
    }
}
