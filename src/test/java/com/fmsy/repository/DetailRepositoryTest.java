package com.fmsy.repository;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.model.Detail;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DetailRepository Tests")
class DetailRepositoryTest {

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private JdbcTemplateWrapper jdbcTemplate;

    private DetailRepository detailRepository;

    @BeforeEach
    void setUp() {
        detailRepository = new DetailRepository(dbPool);
        when(dbPool.getJdbcTemplate("DB_DEFAULT")).thenReturn(jdbcTemplate);
    }

    @Nested
    @DisplayName("SQL constants verification")
    class SqlConstantsTests {

        @Test
        @DisplayName("SELECT_FIELDS should contain all required columns")
        void selectFieldsShouldContainRequiredColumns() {
            when(jdbcTemplate.query(anyString(), any(), any())).thenReturn(List.of());
            detailRepository.findByCommandId(1L);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(), eq(1L));
            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("自增列"), "SQL should reference ID");
            assertTrue(sql.contains("对应指令ID"), "SQL should reference DETAIL_COMMAND_ID");
            assertTrue(sql.contains("类别代号"), "SQL should reference CATEGORY_CODE");
            assertTrue(sql.contains("控制代号"), "SQL should reference CONTROL_CODE");
            assertTrue(sql.contains("指定字段取值"), "SQL should reference FIELD_VALUE");
            assertTrue(sql.contains("稽核数"), "SQL should reference AUDIT_COUNT");
            assertTrue(sql.contains("处理状态"), "SQL should reference PROCESS_STATUS");
        }

        @Test
        @DisplayName("SQL_FIND_DETAILS_BY_COMMAND_ID should reference DETAIL_COMMAND_ID")
        void sqlFindDetailsShouldReferenceDetailCommandId() {
            when(jdbcTemplate.query(anyString(), any(), any())).thenReturn(List.of());
            detailRepository.findByCommandId(1L);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(), eq(1L));
            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("对应指令ID"), "SQL should reference DETAIL_COMMAND_ID");
        }
    }

    @Nested
    @DisplayName("findByCommandId")
    class FindByCommandIdTests {

        @Test
        @DisplayName("should return list of Details")
        void shouldReturnListOfDetails() {
            when(jdbcTemplate.query(anyString(), any(), any())).thenReturn(List.of());
            List<Detail> result = detailRepository.findByCommandId(1L);
            assertNotNull(result);
        }

        @Test
        @DisplayName("should pass correct commandId parameter")
        void shouldPassCorrectCommandIdParameter() {
            when(jdbcTemplate.query(anyString(), any(), any())).thenReturn(List.of());
            detailRepository.findByCommandId(100L);

            verify(jdbcTemplate).query(anyString(), any(), eq(100L));
        }
    }

    @Nested
    @DisplayName("findReadyBuckets")
    class FindReadyBucketsTests {

        @Test
        @DisplayName("should pass STATUS_EMPTY and limit parameters")
        void shouldPassStatusEmptyAndLimitParameters() {
            when(jdbcTemplate.query(anyString(), any(), any(), any())).thenReturn(List.of());
            detailRepository.findReadyBuckets(1L, 10);

            verify(jdbcTemplate).query(anyString(), any(), eq(1L), eq(""), eq(10));
        }
    }

    @Nested
    @DisplayName("competeBucket")
    class CompeteBucketTests {

        @Test
        @DisplayName("should return 1 when competition succeeds")
        void shouldReturnOneWhenCompetitionSucceeds() {
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);
            int result = detailRepository.competeBucket(1L, "node-1");
            assertEquals(1, result);
        }

        @Test
        @DisplayName("should return 0 when competition fails")
        void shouldReturnZeroWhenCompetitionFails() {
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(0);
            int result = detailRepository.competeBucket(1L, "node-1");
            assertEquals(0, result);
        }
    }

    @Nested
    @DisplayName("countEmptyBuckets")
    class CountEmptyBucketsTests {

        @Test
        @DisplayName("should return count from query")
        void shouldReturnCountFromQuery() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(5);
            int count = detailRepository.countEmptyBuckets(1L);
            assertEquals(5, count);
        }

        @Test
        @DisplayName("should return zero when count is null")
        void shouldReturnZeroWhenCountIsNull() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
            int count = detailRepository.countEmptyBuckets(1L);
            assertEquals(0, count);
        }
    }

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatusTests {

        @Test
        @DisplayName("should call update with correct parameters")
        void shouldCallUpdateWithCorrectParameters() {
            when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);
            detailRepository.updateStatus(1L, "Y", "node-1");

            verify(jdbcTemplate).update(anyString(), eq("Y"), eq("node-1"), eq(1L));
        }
    }

    @Nested
    @DisplayName("updateAuditCount")
    class UpdateAuditCountTests {

        @Test
        @DisplayName("should call update with auditCount and detailId")
        void shouldCallUpdateWithAuditCountAndDetailId() {
            when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
            detailRepository.updateAuditCount(1L, 500);

            verify(jdbcTemplate).update(anyString(), eq(500), eq(1L));
        }
    }

    @Nested
    @DisplayName("createBuckets")
    class CreateBucketsTests {

        @Test
        @DisplayName("should not execute when bucketValues is null")
        void shouldNotExecuteWhenBucketValuesIsNull() {
            detailRepository.createBuckets(1L, null, "REGION", "CAT", "CTRL");
            verify(jdbcTemplate, never()).update(anyString(), any());
        }

        @Test
        @DisplayName("should not execute when bucketValues is empty")
        void shouldNotExecuteWhenBucketValuesIsEmpty() {
            detailRepository.createBuckets(1L, List.of(), "REGION", "CAT", "CTRL");
            verify(jdbcTemplate, never()).update(anyString(), any());
        }

        @Test
        @DisplayName("should create insert SQL with correct parameter count for single bucket")
        void shouldCreateInsertSqlWithCorrectParameterCountForSingleBucket() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            detailRepository.createBuckets(1L, List.of("BUCKET_A"), "REGION", "CAT001", "CTRL001");

            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(anyString(), paramsCaptor.capture());
            Object[] params = paramsCaptor.getValue();
            assertEquals(6, params.length); // 1 row * 6 params
        }

        @Test
        @DisplayName("should create insert SQL with correct parameter count for multiple buckets")
        void shouldCreateInsertSqlWithCorrectParameterCountForMultipleBuckets() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(3);
            detailRepository.createBuckets(1L, List.of("A", "B", "C"), "REGION", "CAT001", "CTRL001");

            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(anyString(), paramsCaptor.capture());
            Object[] params = paramsCaptor.getValue();
            assertEquals(18, params.length); // 3 rows * 6 params each
        }
    }
}
