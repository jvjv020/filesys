package com.fmsy.repository;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.enums.CommandType;
import com.fmsy.model.Command;
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
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CommandRepository Tests")
class CommandRepositoryTest {

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private JdbcTemplateWrapper jdbcTemplate;

    private CommandRepository commandRepository;
    private ResultRepository resultRepository;

    @BeforeEach
    void setUp() {
        resultRepository = new ResultRepository(dbPool);
        commandRepository = new CommandRepository(dbPool, resultRepository);
        when(dbPool.getJdbcTemplate("DB_DEFAULT")).thenReturn(jdbcTemplate);
    }

    @Nested
    @DisplayName("SQL constants verification")
    class SqlConstantsTests {

        @Test
        @DisplayName("SQL_UPDATE_STATUS should contain correct table and column names")
        void sqlUpdateStatusShouldContainCorrectNames() {
            when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
            commandRepository.updateStatus(1L, "Y");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(), any());
            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("处理状态"), "SQL should reference PROCESS_STATUS");
            assertTrue(sql.contains("处理结束时间"), "SQL should reference PROCESS_END_TIME");
            assertTrue(sql.contains("自增列"), "SQL should reference ID column");
        }

        @Test
        @DisplayName("SQL_COMPETE_COMMAND should reference PROCESSING_NODE and STATUS")
        void sqlCompeteCommandShouldReferenceCorrectColumns() {
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);
            commandRepository.compete(1L, "node-1");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(), any(), any(), any());
            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("自增列"), "SQL should reference ID column");
            assertTrue(sql.contains("处理状态"), "SQL should reference PROCESS_STATUS column");
            assertTrue(sql.contains("处理节点"), "SQL should reference PROCESSING_NODE column");
        }
    }

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatusTests {

        @Test
        @DisplayName("should call update with correct parameters")
        void shouldCallUpdateWithCorrectParameters() {
            when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
            commandRepository.updateStatus(1L, "Y");

            verify(jdbcTemplate).update(anyString(), eq("Y"), eq(1L));
        }

        @Test
        @DisplayName("should handle null status")
        void shouldHandleNullStatus() {
            when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
            commandRepository.updateStatus(1L, null);

            verify(jdbcTemplate).update(anyString(), isNull(), eq(1L));
        }
    }

    @Nested
    @DisplayName("compete")
    class CompeteTests {

        @Test
        @DisplayName("should return true when update affects 1 row")
        void shouldReturnTrueWhenUpdateAffectsOneRow() {
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);
            boolean result = commandRepository.compete(1L, "node-1");
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when update affects 0 rows")
        void shouldReturnFalseWhenUpdateAffectsZeroRows() {
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(0);
            boolean result = commandRepository.compete(1L, "node-1");
            assertFalse(result);
        }

        @Test
        @DisplayName("should return false when DataAccessException occurs")
        void shouldReturnFalseWhenDataAccessExceptionOccurs() {
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessException("DB error") {});
            boolean result = commandRepository.compete(1L, "node-1");
            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("markErrorWithResult")
    class MarkErrorWithResultTests {

        @Test
        @DisplayName("should call updateStatus and resultRepository.insertSimple")
        void shouldCallUpdateStatusAndInsertSimple() {
            when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
            when(dbPool.resolveJdbcTemplate(any())).thenReturn(jdbcTemplate);
            commandRepository.markErrorWithResult(1L, "CAT001", "CTRL001", "Config not found");

            verify(jdbcTemplate).update(anyString(), eq("E"), eq(1L));
        }
    }

    @Nested
    @DisplayName("findReadyCommands")
    class FindReadyCommandsTests {

        @Test
        @DisplayName("should return list of Command objects")
        void shouldReturnListOfCommands() {
            when(jdbcTemplate.query(anyString(), any(), any(), anyInt())).thenReturn(List.of());
            List<Command> result = commandRepository.findReadyCommands(20);
            assertNotNull(result);
        }

        @Test
        @DisplayName("should pass correct limit parameter")
        void shouldPassCorrectLimitParameter() {
            when(jdbcTemplate.query(anyString(), any(), any(), anyInt())).thenReturn(List.of());
            commandRepository.findReadyCommands(10);

            verify(jdbcTemplate).query(anyString(), any(), eq(""), eq(10));
        }
    }

    @Nested
    @DisplayName("findById")
    class FindByIdTests {

        @Test
        @DisplayName("should return Command when found")
        void shouldReturnCommandWhenFound() {
            Map<String, Object> row = Map.of(
                "自增列", 1L,
                "类别代号", "CAT001",
                "控制代号", "CTRL001",
                "指令类型", "R",
                "稽核数", 100,
                "额外信息", "extra",
                "temp_config", "{\"key\":\"value\"}"
            );
            when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of(row));

            Command result = commandRepository.findById(1L);

            assertNotNull(result);
            assertEquals(1L, result.getId());
            assertEquals("CAT001", result.getCategoryCode());
            assertEquals(CommandType.BATCH, result.getCommandType());
        }

        @Test
        @DisplayName("should return null when not found")
        void shouldReturnNullWhenNotFound() {
            when(jdbcTemplate.queryForList(anyString(), any())).thenReturn(List.of());

            Command result = commandRepository.findById(999L);

            assertNull(result);
        }

        @Test
        @DisplayName("should handle null commandType as SERIAL")
        void shouldHandleNullCommandTypeAsSerial() {
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("自增列", 1L);
            row.put("类别代号", "CAT001");
            row.put("控制代号", "CTRL001");
            row.put("指令类型", null);
            row.put("稽核数", 100);
            when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of(row));

            Command result = commandRepository.findById(1L);

            assertEquals(CommandType.SERIAL, result.getCommandType());
        }
    }

    @Nested
    @DisplayName("releaseTimeoutCommands")
    class ReleaseTimeoutCommandsTests {

        @Test
        @DisplayName("should format SQL with hours parameter")
        void shouldFormatSqlWithHoursParameter() {
            when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(0);
            commandRepository.releaseTimeoutCommands("node-1", 2, "E");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(), any(), any());
            assertTrue(sqlCaptor.getValue().contains("2"));
        }
    }

    @Nested
    @DisplayName("batchCreateChildCommands")
    class BatchCreateChildCommandsTests {

        @Test
        @DisplayName("should not execute when count is zero or negative")
        void shouldNotExecuteWhenCountIsZeroOrNegative() {
            commandRepository.batchCreateChildCommands(0, "CAT", "CTRL", "info", 100);
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("should create batch insert SQL with correct parameter count")
        void shouldCreateBatchInsertSqlWithCorrectParameterCount() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(3);
            commandRepository.batchCreateChildCommands(3, "CAT001", "CTRL001", "mainId|path", -1);

            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(anyString(), paramsCaptor.capture());
            Object[] params = paramsCaptor.getValue();
            assertEquals(15, params.length); // 3 rows * 5 params each
        }
    }

    @Nested
    @DisplayName("countCompletedChildren")
    class CountCompletedChildrenTests {

        @Test
        @DisplayName("should return count from query")
        void shouldReturnCountFromQuery() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any()))
                .thenReturn(5);
            int count = commandRepository.countCompletedChildren("1|%");
            assertEquals(5, count);
        }

        @Test
        @DisplayName("should return zero when count is null")
        void shouldReturnZeroWhenCountIsNull() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any()))
                .thenReturn(null);
            int count = commandRepository.countCompletedChildren("1|%");
            assertEquals(0, count);
        }
    }
}
