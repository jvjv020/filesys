package com.fmsy.repository;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.model.Result;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ResultRepository Tests")
class ResultRepositoryTest {

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private JdbcTemplateWrapper jdbcTemplate;

    private ResultRepository resultRepository;

    @BeforeEach
    void setUp() {
        resultRepository = new ResultRepository(dbPool);
    }

    @Nested
    @DisplayName("SQL constants verification")
    class SqlConstantsTests {

        @Test
        @DisplayName("SQL_INSERT_FULL should contain all 14 columns")
        void sqlInsertFullShouldContainAll14Columns() {
            when(dbPool.resolveJdbcTemplate(any())).thenReturn(jdbcTemplate);
            Result result = Result.builder()
                .commandId(1L)
                .categoryCode("CAT")
                .controlCode("CTRL")
                .ftpName("ftp")
                .filePath("/path")
                .dbInfo("table")
                .transmissionDate(LocalDate.now())
                .status("Y")
                .startTime(LocalDateTime.now())
                .durationMs(100L)
                .recordCount(10)
                .fileSize(1000L)
                .description("desc")
                .transferDirection("UPLOAD")
                .build();

            resultRepository.insert(result);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(),
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("指令ID"));
            assertTrue(sql.contains("类别代号"));
            assertTrue(sql.contains("控制代号"));
            assertTrue(sql.contains("FTP名称"));
            assertTrue(sql.contains("文件路径"));
            assertTrue(sql.contains("数据库信息"));
            assertTrue(sql.contains("传输日期"));
            assertTrue(sql.contains("处理结果"));
            assertTrue(sql.contains("处理起始时间"));
            assertTrue(sql.contains("处理耗时ms"));
            assertTrue(sql.contains("数据记录数量"));
            assertTrue(sql.contains("文件大小"));
            assertTrue(sql.contains("结果说明"));
            assertTrue(sql.contains("传输方向"));
        }
    }

    @Nested
    @DisplayName("insert")
    class InsertTests {

        @Test
        @DisplayName("should call update with all 14 result fields")
        void shouldCallUpdateWithAll14ResultFields() {
            when(dbPool.resolveJdbcTemplate(any())).thenReturn(jdbcTemplate);
            Result result = Result.builder()
                .commandId(1L)
                .categoryCode("CAT001")
                .controlCode("CTRL001")
                .ftpName("ftp1")
                .filePath("/data/file.csv")
                .dbInfo("employees")
                .transmissionDate(LocalDate.of(2024, 1, 15))
                .status("Y")
                .startTime(LocalDateTime.of(2024, 1, 15, 10, 0, 0))
                .durationMs(1500L)
                .recordCount(1000)
                .fileSize(50000L)
                .description("Success")
                .transferDirection(Result.DIRECTION_UPLOAD)
                .build();

            resultRepository.insert(result);

            verify(jdbcTemplate).update(anyString(),
                eq(1L), eq("CAT001"), eq("CTRL001"), eq("ftp1"), eq("/data/file.csv"),
                eq("employees"), eq(LocalDate.of(2024, 1, 15)), eq("Y"),
                eq(LocalDateTime.of(2024, 1, 15, 10, 0, 0)), eq(1500L), eq(1000),
                eq(50000L), eq("Success"), eq(Result.DIRECTION_UPLOAD));
        }

        @Test
        @DisplayName("should use dbName from result for routing")
        void shouldUseDbNameFromResultForRouting() {
            when(dbPool.resolveJdbcTemplate("DB_PRIMARY")).thenReturn(jdbcTemplate);
            Result result = Result.builder()
                .dbName("DB_PRIMARY")
                .commandId(1L)
                .status("Y")
                .build();

            resultRepository.insert(result);

            verify(dbPool).resolveJdbcTemplate("DB_PRIMARY");
        }

        @Test
        @DisplayName("should fall back to DEFAULT_DB when dbName is null")
        void shouldFallBackToDefaultDbWhenDbNameIsNull() {
            when(dbPool.resolveJdbcTemplate(isNull())).thenReturn(jdbcTemplate);
            Result result = Result.builder()
                .dbName(null)
                .commandId(1L)
                .status("Y")
                .build();

            resultRepository.insert(result);

            verify(dbPool).resolveJdbcTemplate(isNull());
        }
    }

    @Nested
    @DisplayName("insertSimple (6 columns)")
    class InsertSimpleTests {

        @Test
        @DisplayName("should insert with 6 parameters")
        void shouldInsertWith6Parameters() {
            when(dbPool.resolveJdbcTemplate("DB_DEFAULT")).thenReturn(jdbcTemplate);
            resultRepository.insertSimple(1L, "CAT001", "CTRL001", "E", "Config not found", "DB_DEFAULT");

            verify(jdbcTemplate).update(anyString(), eq(1L), eq("CAT001"), eq("CTRL001"),
                eq("E"), eq("Config not found"), any(LocalDate.class));
        }

        @Test
        @DisplayName("should use default DB when dbName not specified")
        void shouldUseDefaultDbWhenDbNameNotSpecified() {
            when(dbPool.resolveJdbcTemplate("DB_DEFAULT")).thenReturn(jdbcTemplate);
            resultRepository.insertSimple(1L, "CAT001", "CTRL001", "Y", "Success");

            verify(dbPool).resolveJdbcTemplate("DB_DEFAULT");
        }

        @Test
        @DisplayName("should handle empty dbName parameter")
        void shouldHandleEmptyDbNameParameter() {
            when(dbPool.resolveJdbcTemplate(any())).thenReturn(jdbcTemplate);
            resultRepository.insertSimple(1L, "CAT001", "CTRL001", "E", "Error", "");

            verify(dbPool).resolveJdbcTemplate("DB_DEFAULT");
        }

        @Test
        @DisplayName("should pass all parameters correctly")
        void shouldPassAllParametersCorrectly() {
            when(dbPool.resolveJdbcTemplate(any())).thenReturn(jdbcTemplate);
            resultRepository.insertSimple(100L, "CAT_A", "CTRL_B", "N", "Skipped", "DB_SECONDARY");

            verify(jdbcTemplate).update(anyString(), eq(100L), eq("CAT_A"), eq("CTRL_B"),
                eq("N"), eq("Skipped"), any(LocalDate.class));
        }
    }
}
