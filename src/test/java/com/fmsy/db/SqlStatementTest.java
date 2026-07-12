package com.fmsy.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SqlStatement Tests")
class SqlStatementTest {

    @Nested
    @DisplayName("construction")
    class ConstructionTests {

        @Test
        @DisplayName("should store SQL and params")
        void shouldStoreSqlAndParams() {
            List<Object> params = Arrays.asList("value1", 42, true);
            SqlStatement stmt = new SqlStatement("SELECT * FROM test WHERE id = ?", params);
            assertEquals("SELECT * FROM test WHERE id = ?", stmt.getSql());
            assertEquals(params, stmt.getParams());
        }

        @Test
        @DisplayName("should convert null params to empty list")
        void shouldConvertNullParamsToEmptyList() {
            SqlStatement stmt = new SqlStatement("DELETE FROM test", null);
            assertEquals("DELETE FROM test", stmt.getSql());
            assertNotNull(stmt.getParams());
            assertTrue(stmt.getParams().isEmpty());
        }

        @Test
        @DisplayName("should create defensive copy of params list")
        void shouldCreateDefensiveCopyOfParams() {
            List<Object> original = new ArrayList<>();
            original.add("value1");
            SqlStatement stmt = new SqlStatement("SELECT * FROM test WHERE id = ?", original);

            original.add("injected");

            assertEquals(1, stmt.getParams().size());
            assertFalse(stmt.getParams().contains("injected"));
        }
    }

    @Nested
    @DisplayName("accessors")
    class AccessorTests {

        @Test
        @DisplayName("getSql should return the SQL string")
        void getSqlShouldReturnSql() {
            SqlStatement stmt = new SqlStatement("SELECT COUNT(*) FROM users", List.of());
            assertEquals("SELECT COUNT(*) FROM users", stmt.getSql());
        }

        @Test
        @DisplayName("getParams should return params list")
        void getParamsShouldReturnParams() {
            SqlStatement stmt = new SqlStatement("SELECT * FROM t WHERE a = ? AND b = ?",
                    Arrays.asList("x", 100));
            assertEquals(2, stmt.getParams().size());
            assertEquals("x", stmt.getParams().get(0));
            assertEquals(100, stmt.getParams().get(1));
        }

        @Test
        @DisplayName("getParamArray should convert to array")
        void getParamArrayShouldReturnArray() {
            SqlStatement stmt = new SqlStatement("SELECT * FROM t WHERE a = ?",
                    List.of("test-value"));
            Object[] array = stmt.getParamArray();
            assertInstanceOf(Object[].class, array);
            assertEquals(1, array.length);
            assertEquals("test-value", array[0]);
        }

        @Test
        @DisplayName("getParamArray should return empty array for empty params")
        void getParamArrayShouldReturnEmptyForEmptyParams() {
            SqlStatement stmt = new SqlStatement("SELECT 1", new ArrayList<>());
            Object[] array = stmt.getParamArray();
            assertEquals(0, array.length);
        }
    }

    @Nested
    @DisplayName("immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("getParams should return the internal list (not a copy)")
        void getParamsShouldReturnInternalList() {
            SqlStatement stmt = new SqlStatement("SELECT * FROM t WHERE a = ?",
                    new ArrayList<>(List.of("original")));

            List<Object> retrieved = stmt.getParams();
            retrieved.add("modified");

            assertEquals(2, stmt.getParams().size());
            assertTrue(stmt.getParams().contains("modified"));
        }

        @Test
        @DisplayName("getParamArray should not affect internal state")
        void getParamArrayShouldNotAffectInternalState() {
            SqlStatement stmt = new SqlStatement("SELECT * FROM t WHERE a = ?",
                    new ArrayList<>(List.of("value")));

            Object[] array1 = stmt.getParamArray();
            Object[] array2 = stmt.getParamArray();

            assertNotSame(array1, array2);
            assertArrayEquals(array1, array2);
        }
    }
}
