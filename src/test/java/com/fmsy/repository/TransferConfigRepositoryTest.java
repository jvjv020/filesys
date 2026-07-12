package com.fmsy.repository;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.model.TransferConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferConfigRepository Tests")
class TransferConfigRepositoryTest {

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private JdbcTemplateWrapper jdbcTemplate;

    private TransferConfigRepository transferConfigRepository;

    @BeforeEach
    void setUp() {
        transferConfigRepository = new TransferConfigRepository(dbPool);
        when(dbPool.getJdbcTemplate("DB_DEFAULT")).thenReturn(jdbcTemplate);
    }

    @Nested
    @DisplayName("SQL constants verification")
    class SqlConstantsTests {

        @Test
        @DisplayName("SQL_LOAD_ALL should reference STATUS column with parameter")
        void sqlLoadAllShouldReferenceStatusColumn() {
            when(jdbcTemplate.query(anyString(), any(), any())).thenReturn(List.of());
            transferConfigRepository.loadAll();

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(), eq("有效"));
            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("状态"), "SQL should reference STATUS column");
        }
    }

    @Nested
    @DisplayName("loadAll")
    class LoadAllTests {

        @Test
        @DisplayName("should return list of TransferConfig")
        void shouldReturnListOfTransferConfig() {
            when(jdbcTemplate.query(anyString(), any(), any())).thenReturn(List.of());
            List<TransferConfig> result = transferConfigRepository.loadAll();
            assertNotNull(result);
        }

        @Test
        @DisplayName("should pass STATUS_VALID as parameter")
        void shouldPassStatusValidAsParameter() {
            when(jdbcTemplate.query(anyString(), any(), any())).thenReturn(List.of());
            transferConfigRepository.loadAll();

            verify(jdbcTemplate).query(anyString(), any(), eq("有效"));
        }

        @Test
        @DisplayName("should use DEFAULT_DB for jdbcTemplate lookup")
        void shouldUseDefaultDbForJdbcTemplateLookup() {
            when(jdbcTemplate.query(anyString(), any(), any())).thenReturn(List.of());
            transferConfigRepository.loadAll();

            verify(dbPool).getJdbcTemplate("DB_DEFAULT");
        }
    }
}
