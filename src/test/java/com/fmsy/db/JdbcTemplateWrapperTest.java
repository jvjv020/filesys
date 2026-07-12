package com.fmsy.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JdbcTemplateWrapper Tests")
class JdbcTemplateWrapperTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcTemplateWrapper wrapper;

    @BeforeEach
    void setUp() {
        wrapper = new JdbcTemplateWrapper(jdbcTemplate);
    }

    @Nested
    @DisplayName("query operations")
    class QueryOperationsTests {

        @Test
        @DisplayName("query should delegate to JdbcTemplate.query with params")
        void queryShouldDelegateWithParams() {
            RowMapper<String> mapper = (rs, rowNum) -> rs.getString("name");
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(123)))
                    .thenReturn(List.of("result1", "result2"));

            List<String> results = wrapper.query("SELECT name FROM users WHERE id = ?", mapper, 123);

            assertEquals(2, results.size());
            verify(jdbcTemplate).query("SELECT name FROM users WHERE id = ?", mapper, 123);
        }

        @Test
        @DisplayName("query should forward multiple params correctly")
        void queryShouldForwardMultipleParams() {
            RowMapper<String> mapper = (rs, rowNum) -> rs.getString("name");
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1), eq("ACTIVE")))
                    .thenReturn(List.of());

            wrapper.query("SELECT name FROM users WHERE id = ? AND status = ?", mapper, 1, "ACTIVE");

            verify(jdbcTemplate).query(
                    "SELECT name FROM users WHERE id = ? AND status = ?", mapper, 1, "ACTIVE");
        }

        @Test
        @DisplayName("query should return empty list when no results")
        void queryShouldReturnEmptyListWhenNoResults() {
            RowMapper<String> mapper = (rs, rowNum) -> "";
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(new Object[0])))
                    .thenReturn(List.of());

            List<String> results = wrapper.query("SELECT name FROM users WHERE 1=0", mapper);

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("queryForList should delegate to JdbcTemplate.queryForList with params")
        void queryForListShouldDelegate() {
            when(jdbcTemplate.queryForList("SELECT * FROM users WHERE id = ?", 100))
                    .thenReturn(List.of(Map.of("id", 1)));

            List<Map<String, Object>> results = wrapper.queryForList(
                    "SELECT * FROM users WHERE id = ?", 100);

            assertEquals(1, results.size());
            verify(jdbcTemplate).queryForList("SELECT * FROM users WHERE id = ?", 100);
        }

        @Test
        @DisplayName("queryForObject should delegate to JdbcTemplate.queryForObject with params")
        void queryForObjectShouldDelegate() {
            when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE status = ?",
                    Integer.class, "ACTIVE")).thenReturn(42);

            Integer result = wrapper.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE status = ?", Integer.class, "ACTIVE");

            assertEquals(42, result);
            verify(jdbcTemplate).queryForObject(
                    "SELECT COUNT(*) FROM users WHERE status = ?", Integer.class, "ACTIVE");
        }
    }

    @Nested
    @DisplayName("update operations")
    class UpdateOperationsTests {

        @Test
        @DisplayName("update should delegate to JdbcTemplate.update with params")
        void updateShouldDelegate() {
            when(jdbcTemplate.update("UPDATE users SET name = ? WHERE id = ?", "new-name", 42))
                    .thenReturn(1);

            int rows = wrapper.update("UPDATE users SET name = ? WHERE id = ?", "new-name", 42);

            assertEquals(1, rows);
            verify(jdbcTemplate).update("UPDATE users SET name = ? WHERE id = ?", "new-name", 42);
        }

        @Test
        @DisplayName("batchUpdate should delegate to JdbcTemplate.batchUpdate")
        void batchUpdateShouldDelegate() {
            List<Object[]> batchArgs = Arrays.asList(
                    new Object[]{1, "a"},
                    new Object[]{2, "b"}
            );
            when(jdbcTemplate.batchUpdate(
                    "INSERT INTO users (id, name) VALUES (?, ?)", batchArgs))
                    .thenReturn(new int[]{1, 1});

            int[] results = wrapper.batchUpdate(
                    "INSERT INTO users (id, name) VALUES (?, ?)", batchArgs);

            assertArrayEquals(new int[]{1, 1}, results);
            verify(jdbcTemplate).batchUpdate(
                    "INSERT INTO users (id, name) VALUES (?, ?)", batchArgs);
        }

        @Test
        @DisplayName("update should return 0 when no rows affected")
        void updateShouldReturnZeroWhenNoRowsAffected() {
            when(jdbcTemplate.update("DELETE FROM users WHERE id = ?", 999)).thenReturn(0);

            int rows = wrapper.update("DELETE FROM users WHERE id = ?", 999);

            assertEquals(0, rows);
            verify(jdbcTemplate).update("DELETE FROM users WHERE id = ?", 999);
        }
    }

    @Nested
    @DisplayName("SqlStatement overloads")
    class SqlStatementOverloadsTests {

        @Test
        @DisplayName("queryForList with SqlStatement should extract params")
        void queryForListWithSqlStatement() {
            SqlStatement stmt = new SqlStatement("SELECT * FROM users WHERE status = ?",
                    List.of("ACTIVE"));
            when(jdbcTemplate.queryForList("SELECT * FROM users WHERE status = ?", "ACTIVE"))
                    .thenReturn(List.of(Map.of("id", 1)));

            wrapper.queryForList(stmt);

            verify(jdbcTemplate).queryForList("SELECT * FROM users WHERE status = ?", "ACTIVE");
        }

        @Test
        @DisplayName("queryForObject with SqlStatement should extract params")
        void queryForObjectWithSqlStatement() {
            SqlStatement stmt = new SqlStatement("SELECT COUNT(*) FROM users WHERE id > ?",
                    List.of(0));
            when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id > ?",
                    Integer.class, 0)).thenReturn(100);

            Integer result = wrapper.queryForObject(stmt, Integer.class);

            assertEquals(100, result);
            verify(jdbcTemplate).queryForObject("SELECT COUNT(*) FROM users WHERE id > ?",
                    Integer.class, 0);
        }

        @Test
        @DisplayName("SqlStatement overload should pass multiple params correctly")
        void sqlStatementShouldPassMultipleParams() {
            SqlStatement stmt = new SqlStatement(
                    "SELECT * FROM orders WHERE status = ? AND amount > ?",
                    Arrays.asList("PAID", 100.50));
            when(jdbcTemplate.queryForList(
                    "SELECT * FROM orders WHERE status = ? AND amount > ?", "PAID", 100.50))
                    .thenReturn(List.of());

            wrapper.queryForList(stmt);

            verify(jdbcTemplate).queryForList(
                    "SELECT * FROM orders WHERE status = ? AND amount > ?", "PAID", 100.50);
        }

        @Test
        @DisplayName("SqlStatement overload with empty params should pass no extra args")
        void sqlStatementWithEmptyParams() {
            SqlStatement stmt = new SqlStatement("SELECT 1", List.of());
            when(jdbcTemplate.queryForList("SELECT 1", new Object[0]))
                    .thenReturn(List.of(Map.of("?column?", 1)));

            List<Map<String, Object>> result = wrapper.queryForList(stmt);

            assertEquals(1, result.size());
            verify(jdbcTemplate).queryForList("SELECT 1", new Object[0]);
        }
    }
}
