package com.fmsy.db;

import com.fmsy.config.DataSourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PartitionHelper Tests")
class PartitionHelperTest {

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private JdbcTemplateWrapper jdbcTemplate;

    private PartitionHelper partitionHelper;

    @BeforeEach
    void setUp() {
        partitionHelper = new PartitionHelper(dbPool);
        when(dbPool.resolveJdbcTemplate(anyString())).thenReturn(jdbcTemplate);
        when(dbPool.resolveJdbcTemplate(isNull())).thenReturn(jdbcTemplate);
    }

    @Nested
    @DisplayName("isPartitioned")
    class IsPartitionedTests {

        @Test
        @DisplayName("should return true when table has partitions")
        void shouldReturnTrueWhenHasPartitions() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenReturn(List.of(Map.of("partition_name", "part1")));
            // Also mock the primary key query
            when(jdbcTemplate.queryForList(eq(
                    "SELECT kcu.column_name " +
                    "FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.key_column_usage kcu " +
                    "  ON tc.constraint_catalog = kcu.constraint_catalog " +
                    "  AND tc.constraint_schema = kcu.constraint_schema " +
                    "  AND tc.constraint_name = kcu.constraint_name " +
                    "WHERE tc.table_name = ? " +
                    "  AND tc.constraint_type = 'PRIMARY KEY' " +
                    "ORDER BY kcu.ordinal_position"), anyString()))
                    .thenReturn(List.of());

            boolean result = partitionHelper.isPartitioned("DB1", "mytable");
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when table has no partitions")
        void shouldReturnFalseWhenNoPartitions() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(eq(
                    "SELECT kcu.column_name " +
                    "FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.key_column_usage kcu " +
                    "  ON tc.constraint_catalog = kcu.constraint_catalog " +
                    "  AND tc.constraint_schema = kcu.constraint_schema " +
                    "  AND tc.constraint_name = kcu.constraint_name " +
                    "WHERE tc.table_name = ? " +
                    "  AND tc.constraint_type = 'PRIMARY KEY' " +
                    "ORDER BY kcu.ordinal_position"), anyString()))
                    .thenReturn(List.of());

            boolean result = partitionHelper.isPartitioned("DB1", "mytable");
            assertFalse(result);
        }

        @Test
        @DisplayName("should handle null table name")
        void shouldHandleNullTableName() {
            boolean result = partitionHelper.isPartitioned("DB1", null);
            assertFalse(result);
        }

        @Test
        @DisplayName("should handle query exception gracefully")
        void shouldHandleException() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenThrow(new RuntimeException("DB error"));

            boolean result = partitionHelper.isPartitioned("DB1", "mytable");
            assertFalse(result);
        }

        @Test
        @DisplayName("should cache results")
        void shouldCacheResults() {
            when(jdbcTemplate.queryForList(eq(
                    "SELECT c.relname AS partition_name " +
                    "FROM pg_catalog.pg_class c " +
                    "JOIN pg_catalog.pg_inherits i ON c.oid = i.inhrelid " +
                    "WHERE i.inhparent = (SELECT oid FROM pg_catalog.pg_class WHERE relname = ?) " +
                    "ORDER BY c.relname"), anyString()))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("PRIMARY KEY"), anyString()))
                    .thenReturn(List.of());

            // First call
            partitionHelper.isPartitioned("DB1", "mytable");
            // Second call should use cache
            partitionHelper.isPartitioned("DB1", "mytable");

            verify(jdbcTemplate, times(1)).queryForList(
                    contains("pg_catalog.pg_inherits"), anyString());
        }
    }

    @Nested
    @DisplayName("getPartitions")
    class GetPartitionsTests {

        @Test
        @DisplayName("should return partition list")
        void shouldReturnPartitionList() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenReturn(List.of(
                            Map.of("partition_name", "part1"),
                            Map.of("partition_name", "part2")));
            when(jdbcTemplate.queryForList(contains("PRIMARY KEY"), anyString()))
                    .thenReturn(List.of());

            List<String> partitions = partitionHelper.getPartitions("DB1", "mytable");

            assertEquals(2, partitions.size());
            assertTrue(partitions.contains("part1"));
            assertTrue(partitions.contains("part2"));
        }

        @Test
        @DisplayName("should return empty list for non-partitioned table")
        void shouldReturnEmptyForNonPartitioned() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("PRIMARY KEY"), anyString()))
                    .thenReturn(List.of());

            List<String> partitions = partitionHelper.getPartitions("DB1", "mytable");
            assertTrue(partitions.isEmpty());
        }
    }

    @Nested
    @DisplayName("getPrimaryKeyColumns")
    class GetPrimaryKeyColumnsTests {

        @Test
        @DisplayName("should return primary key columns")
        void shouldReturnPrimaryKeyColumns() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenReturn(List.of()); // partition query returns empty
            when(jdbcTemplate.queryForList(contains("PRIMARY KEY"), anyString()))
                    .thenReturn(List.of(
                            Map.of("column_name", "id"),
                            Map.of("column_name", "name")));

            List<String> pk = partitionHelper.getPrimaryKeyColumns("DB1", "mytable");

            assertEquals(2, pk.size());
            assertTrue(pk.contains("id"));
            assertTrue(pk.contains("name"));
        }

        @Test
        @DisplayName("should return empty list when no primary key")
        void shouldReturnEmptyWhenNoPrimaryKey() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("PRIMARY KEY"), anyString()))
                    .thenReturn(List.of());

            List<String> pk = partitionHelper.getPrimaryKeyColumns("DB1", "mytable");
            assertTrue(pk.isEmpty());
        }
    }

    @Nested
    @DisplayName("scanPartitionsDistinct")
    class ScanPartitionsDistinctTests {

        @Test
        @DisplayName("should scan partitions and merge distinct results")
        void shouldScanAndMerge() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenReturn(List.of(
                            Map.of("partition_name", "part1"),
                            Map.of("partition_name", "part2")));
            when(jdbcTemplate.queryForList(contains("PRIMARY KEY"), anyString()))
                    .thenReturn(List.of());

            // Mock partition queries return distinct values
            when(jdbcTemplate.queryForList(any(com.fmsy.db.SqlStatement.class)))
                    .thenReturn(
                            List.of(Map.of("col1", "a"), Map.of("col1", "b")),
                            List.of(Map.of("col1", "b"), Map.of("col1", "c"))
                    );

            List<Map<String, Object>> result = partitionHelper.scanPartitionsDistinct(
                    "DB1", "mytable", List.of("col1"), List.of("col1"));

            // Should have merged distinct: a, b, c (b only once)
            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("should return empty list when no partitions")
        void shouldReturnEmptyWhenNoPartitions() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("PRIMARY KEY"), anyString()))
                    .thenReturn(List.of());

            List<Map<String, Object>> result = partitionHelper.scanPartitionsDistinct(
                    "DB1", "mytable", List.of("col1"), List.of("col1"));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return null on query failure to trigger fallback")
        void shouldReturnNullOnFailure() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenReturn(List.of(Map.of("partition_name", "part1")));
            when(jdbcTemplate.queryForList(contains("PRIMARY KEY"), anyString()))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(any(com.fmsy.db.SqlStatement.class)))
                    .thenThrow(new RuntimeException("Query failed"));

            List<Map<String, Object>> result = partitionHelper.scanPartitionsDistinct(
                    "DB1", "mytable", List.of("col1"), List.of("col1"));

            assertNull(result);
        }
    }
}
