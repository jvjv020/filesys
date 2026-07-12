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
@DisplayName("TableMetadataService Tests")
class TableMetadataServiceTest {

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private JdbcTemplateWrapper jdbcTemplate;

    private TableMetadataService tableMetadataService;

    @BeforeEach
    void setUp() {
        tableMetadataService = new TableMetadataService(dbPool);
        when(dbPool.resolveJdbcTemplate(anyString())).thenReturn(jdbcTemplate);
    }

    @Nested
    @DisplayName("getTableColumns")
    class GetTableColumnsTests {

        @Test
        @DisplayName("should return columns in ordinal order")
        void shouldReturnColumnsInOrder() {
            when(jdbcTemplate.queryForList(anyString(), eq("mytable")))
                    .thenReturn(List.of(
                            Map.of("column_name", "id"),
                            Map.of("column_name", "name"),
                            Map.of("column_name", "age")
                    ));

            List<String> columns = tableMetadataService.getTableColumns("DB1", "mytable");

            assertEquals(3, columns.size());
            assertEquals("id", columns.get(0));
            assertEquals("name", columns.get(1));
            assertEquals("age", columns.get(2));
        }

        @Test
        @DisplayName("should use lowercased table name in query")
        void shouldUseLowercasedTableName() {
            when(jdbcTemplate.queryForList(anyString(), eq("MYTABLE"))).thenReturn(List.of());

            tableMetadataService.getTableColumns("DB1", "MYTABLE");

            // Verify that the table name is lowercased
            verify(jdbcTemplate).queryForList(contains("information_schema.columns"), eq("mytable"));
        }

        @Test
        @DisplayName("should return empty list when tableName is null")
        void shouldReturnEmptyListWhenTableNameIsNull() {
            List<String> columns = tableMetadataService.getTableColumns("DB1", null);
            assertTrue(columns.isEmpty());
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("should return empty list when tableName is empty")
        void shouldReturnEmptyListWhenTableNameIsEmpty() {
            List<String> columns = tableMetadataService.getTableColumns("DB1", "");
            assertTrue(columns.isEmpty());
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("should return empty list on query exception")
        void shouldReturnEmptyListOnException() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenThrow(new RuntimeException("DB error"));

            List<String> columns = tableMetadataService.getTableColumns("DB1", "mytable");
            assertTrue(columns.isEmpty());
        }

        @Test
        @DisplayName("should filter out null column names")
        void shouldFilterOutNullColumnNames() {
            java.util.Map<String, Object> nullValueMap = new java.util.HashMap<>();
            nullValueMap.put("column_name", null);
            when(jdbcTemplate.queryForList(anyString(), eq("mytable")))
                    .thenReturn(List.of(
                            Map.of("column_name", "id"),
                            nullValueMap,
                            Map.of("column_name", "name")
                    ));

            List<String> columns = tableMetadataService.getTableColumns("DB1", "mytable");

            assertEquals(2, columns.size());
            assertEquals("id", columns.get(0));
            assertEquals("name", columns.get(1));
        }

        @Test
        @DisplayName("should use information_schema query")
        void shouldUseInformationSchemaQuery() {
            when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());

            tableMetadataService.getTableColumns("DB1", "mytable");

            verify(jdbcTemplate).queryForList(
                    contains("information_schema.columns"), eq("mytable"));
        }
    }
}
