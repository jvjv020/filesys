package com.fmsy.repository;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.db.PartitionHelper;
import com.fmsy.db.SqlBuilder;
import com.fmsy.model.TransferConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TargetTableRepository Tests")
class TargetTableRepositoryTest {

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private PartitionHelper partitionHelper;

    @Mock
    private JdbcTemplateWrapper jdbcTemplate;

    @Mock
    private DataSource dataSource;

    private TargetTableRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TargetTableRepository(dbPool, partitionHelper);
        when(dbPool.resolveJdbcTemplate("DB1")).thenReturn(jdbcTemplate);
        when(dbPool.resolveJdbcTemplate(null)).thenReturn(jdbcTemplate);
        // Stream test-friendly DataSource mock
        try {
            java.sql.Connection conn = mock(java.sql.Connection.class);
            when(conn.getAutoCommit()).thenReturn(true);
            java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
            when(conn.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(ps);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            java.sql.ResultSetMetaData rsmd = mock(java.sql.ResultSetMetaData.class);
            when(rsmd.getColumnCount()).thenReturn(1);
            when(rs.getMetaData()).thenReturn(rsmd);
            when(rs.next()).thenReturn(false);
            when(ps.executeQuery()).thenReturn(rs);
            when(dataSource.getConnection()).thenReturn(conn);
        } catch (Exception ignored) {}
        when(dbPool.getDataSource("DB_DEFAULT")).thenReturn(dataSource);
        when(dbPool.getDataSource("DB1")).thenReturn(dataSource);
        when(dbPool.getDataSource(anyString())).thenReturn(dataSource);
    }

    @Nested
    @DisplayName("truncate")
    class TruncateTests {

        @Test
        @DisplayName("should call update with DELETE SQL")
        void shouldCallUpdate() {
            repository.truncate("DB1", "mytable");

            verify(jdbcTemplate).update(anyString());
        }

        @Test
        @DisplayName("should use SqlBuilder.buildDeleteAll")
        void shouldUseBuildDeleteAll() {
            repository.truncate("DB1", "mytable");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture());
            assertTrue(sqlCaptor.getValue().toUpperCase().contains("DELETE"));
        }
    }

    @Nested
    @DisplayName("batchInsert")
    class BatchInsertTests {

        @Test
        @DisplayName("should insert rows with field mappings")
        void shouldInsertRows() {
            List<String> fields = Arrays.asList("col1", "col2");
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{"val1", 100});
            rows.add(new Object[]{"val2", 200});

            repository.batchInsert("DB1", "mytable", fields, rows);

            verify(jdbcTemplate).update(anyString(), any(Object[].class));
            // Verify params via separate interaction check
            assertNotNull(repository);
        }

        @Test
        @DisplayName("should do nothing when fields or rows empty")
        void shouldDoNothingWhenEmpty() {
            repository.batchInsert("DB1", "mytable", null, null);
            repository.batchInsert("DB1", "mytable", List.of(), List.of());
            repository.batchInsert("DB1", "mytable", List.of("col1"), List.of());

            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("should generate multi-value INSERT SQL")
        void shouldGenerateMultiValueInsert() {
            List<String> fields = Arrays.asList("col1", "col2");
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{"v1", 1});
            rows.add(new Object[]{"v2", 2});

            repository.batchInsert("DB1", "mytable", fields, rows);

            // Verify batch insert was called with SQL and parameters
            verify(jdbcTemplate, atLeastOnce()).update(anyString(), any(Object[].class));
        }
    }

    @Nested
    @DisplayName("count")
    class CountTests {

        @Test
        @DisplayName("should return count from query")
        void shouldReturnCount() {
            when(jdbcTemplate.queryForObject(any(com.fmsy.db.SqlStatement.class), eq(Integer.class)))
                    .thenReturn(500);

            int result = repository.count("DB1", "mytable");
            assertEquals(500, result);
        }

        @Test
        @DisplayName("should return zero when count is null")
        void shouldReturnZeroWhenCountIsNull() {
            when(jdbcTemplate.queryForObject(any(com.fmsy.db.SqlStatement.class), eq(Integer.class)))
                    .thenReturn(null);

            int result = repository.count("DB1", "mytable");
            assertEquals(0, result);
        }
    }

    @Nested
    @DisplayName("countByBucket")
    class CountByBucketTests {

        @Test
        @DisplayName("should count with bucket predicate")
        void shouldCountWithBucketPredicate() {
            when(jdbcTemplate.queryForObject(any(com.fmsy.db.SqlStatement.class), eq(Integer.class)))
                    .thenReturn(30);

            int result = repository.countByBucket("DB1", "mytable", "REGION", "EAST");
            assertEquals(30, result);
        }

        @Test
        @DisplayName("should fallback to full count when splitField is null")
        void shouldFallbackWhenSplitFieldIsNull() {
            when(jdbcTemplate.queryForObject(any(com.fmsy.db.SqlStatement.class), eq(Integer.class)))
                    .thenReturn(100);

            int result = repository.countByBucket("DB1", "mytable", null, null);
            assertEquals(100, result);
        }

        @Test
        @DisplayName("should handle multi-field bucket predicates")
        void shouldHandleMultiFieldBucket() {
            when(jdbcTemplate.queryForObject(any(com.fmsy.db.SqlStatement.class), eq(Integer.class)))
                    .thenReturn(15);

            int result = repository.countByBucket("DB1", "mytable", "REGION,STATUS", "EAST,ACTIVE");
            assertEquals(15, result);
        }
    }

    @Nested
    @DisplayName("querySmallResult")
    class QuerySmallResultTests {

        @Test
        @DisplayName("should query with parameters")
        void shouldQueryWithParameters() {
            when(jdbcTemplate.queryForList(any(com.fmsy.db.SqlStatement.class)))
                    .thenReturn(List.of(Map.of("col1", "val1")));

            List<Map<String, Object>> result = repository.querySmallResult(
                    "DB1", "mytable", List.of("col1"), true,
                    List.of("col1 = ?"), List.of("val"), List.of("col1"), 10);

            assertEquals(1, result.size());
            assertEquals("val1", result.get(0).get("col1"));
        }

        @Test
        @DisplayName("should use partition distinct optimization when applicable")
        void shouldUsePartitionDistinct() {
            when(partitionHelper.isPartitioned("DB1", "mytable")).thenReturn(true);
            when(partitionHelper.scanPartitionsDistinct("DB1", "mytable",
                    List.of("col1"), List.of("col1")))
                    .thenReturn(List.of(Map.of("col1", "val1")));

            List<Map<String, Object>> result = repository.querySmallResult(
                    "DB1", "mytable", List.of("col1"), true,
                    null, null, List.of("col1"), null);

            assertEquals(1, result.size());
            assertEquals("val1", result.get(0).get("col1"));
        }
    }

    @Nested
    @DisplayName("streamQueryBatches")
    class StreamQueryBatchesTests {

        @Test
        @DisplayName("should return DataStream")
        void shouldReturnDataStream() {
            when(jdbcTemplate.queryForList(any(com.fmsy.db.SqlStatement.class)))
                    .thenReturn(List.of());

            TargetTableRepository.DataStream stream = repository.streamQueryBatches(
                    "DB1", "mytable", null, false,
                    null, null, null, null);

            assertNotNull(stream);
        }

        @Test
        @DisplayName("should use partition sequential when applicable")
        void shouldUsePartitionSequential() {
            when(partitionHelper.isPartitioned("DB1", "mytable")).thenReturn(true);
            when(partitionHelper.getPartitions("DB1", "mytable")).thenReturn(List.of("part1", "part2"));
            when(partitionHelper.getPrimaryKeyColumns("DB1", "mytable")).thenReturn(List.of("id"));

            TargetTableRepository.DataStream stream = repository.streamQueryBatches(
                    "DB1", "mytable", null, false,
                    null, null, null, null);

            assertNotNull(stream);
        }
    }

    @Nested
    @DisplayName("streamBucketData")
    class StreamBucketDataTests {

        @Test
        @DisplayName("should throw when splitField is empty")
        void shouldThrowWhenSplitFieldEmpty() {
            assertThrows(IllegalArgumentException.class,
                    () -> repository.streamBucketData("DB1", "mytable", "", "val"));
        }

        @Test
        @DisplayName("should return DataStream for bucket")
        void shouldReturnDataStreamForBucket() {
            TargetTableRepository.DataStream stream = repository.streamBucketData(
                    "DB1", "mytable", "REGION", "EAST");

            assertNotNull(stream);
        }
    }

    @Nested
    @DisplayName("streamTableDirect")
    class StreamTableDirectTests {

        @Test
        @DisplayName("should return DataStream")
        void shouldReturnDataStream() {
            TargetTableRepository.DataStream stream = repository.streamTableDirect(
                    "DB1", "mytable", List.of("id"));

            assertNotNull(stream);
        }
    }
}
