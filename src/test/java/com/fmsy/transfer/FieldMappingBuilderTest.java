package com.fmsy.transfer;

import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.db.TableMetadataService;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.util.ColumnNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FieldMappingBuilder Tests")
class FieldMappingBuilderTest {

    @Mock
    private TableMetadataService tableMetadataService;

    @Mock
    private ConverterFactory converterFactory;

    @Mock
    private FileConverter fileConverter;

    private FieldMappingBuilder fieldMappingBuilder;

    @BeforeEach
    void setUp() {
        fieldMappingBuilder = new FieldMappingBuilder(tableMetadataService, converterFactory);
    }

    @Nested
    @DisplayName("buildForUpload")
    class BuildForUploadTests {

        @Test
        @DisplayName("should return empty mapping when config is null")
        void shouldReturnEmptyMappingWhenConfigIsNull() {
            FieldMapping result = fieldMappingBuilder.buildForUpload(null, null);

            assertNotNull(result);
            assertNull(result.getConfig());
            assertNull(result.getTableFields());
            assertNull(result.getExtraFields());
        }

        @Test
        @DisplayName("should build mapping with table columns excluding ignore fields")
        void shouldBuildMappingWithTableColumnsExcludingIgnoreFields() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_DEFAULT");
            config.setTableName("test_table");
            config.setParserType("CSV");
            config.setIgnoreFields("COL3,col4");

            when(tableMetadataService.getTableColumns("DB_DEFAULT", "test_table"))
                    .thenReturn(List.of("COL1", "COL2", "COL3", "col4", "COL5"));
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(fileConverter.getDefaultConfig()).thenReturn(Map.of("charset", "UTF-8"));

            FieldMapping result = fieldMappingBuilder.buildForUpload(config, null);

            assertEquals(3, result.getTableFields().size());
            assertTrue(result.getTableFields().contains("COL1"));
            assertTrue(result.getTableFields().contains("COL2"));
            assertTrue(result.getTableFields().contains("COL5"));
            assertFalse(result.getTableFields().contains("COL3"));
        }

        @Test
        @DisplayName("should extract extra fields from detail")
        void shouldExtractExtraFieldsFromDetail() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_DEFAULT");
            config.setTableName("test_table");
            config.setParserType("CSV");

            when(tableMetadataService.getTableColumns("DB_DEFAULT", "test_table"))
                    .thenReturn(List.of("COL1", "COL2"));
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(fileConverter.getDefaultConfig()).thenReturn(Map.of());

            Map<String, Object> detail = new HashMap<>();
            detail.put(ColumnNames.FIELD_NAME, "REGION,STATUS");
            detail.put(ColumnNames.FIELD_VALUE, "EAST,ACTIVE");

            FieldMapping result = fieldMappingBuilder.buildForUpload(config, detail);

            assertNotNull(result.getExtraFields());
            assertEquals("EAST", result.getExtraFields().get("REGION"));
            assertEquals("ACTIVE", result.getExtraFields().get("STATUS"));
        }

        @Test
        @DisplayName("should not set extra fields when detail is null")
        void shouldNotSetExtraFieldsWhenDetailIsNull() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_DEFAULT");
            config.setTableName("test_table");
            config.setParserType("CSV");

            when(tableMetadataService.getTableColumns("DB_DEFAULT", "test_table"))
                    .thenReturn(List.of("COL1"));
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(fileConverter.getDefaultConfig()).thenReturn(Map.of());

            FieldMapping result = fieldMappingBuilder.buildForUpload(config, null);

            assertNull(result.getExtraFields());
        }

        @Test
        @DisplayName("should handle detail with missing field name or value")
        void shouldHandleDetailWithMissingFieldNameOrValue() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_DEFAULT");
            config.setTableName("test_table");
            config.setParserType("CSV");

            when(tableMetadataService.getTableColumns("DB_DEFAULT", "test_table"))
                    .thenReturn(List.of("COL1"));
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(fileConverter.getDefaultConfig()).thenReturn(Map.of());

            Map<String, Object> detailMissingNames = new HashMap<>();
            detailMissingNames.put(ColumnNames.FIELD_VALUE, "VALUE");

            Map<String, Object> detailMissingValues = new HashMap<>();
            detailMissingValues.put(ColumnNames.FIELD_NAME, "FIELD");

            FieldMapping result1 = fieldMappingBuilder.buildForUpload(config, detailMissingNames);
            FieldMapping result2 = fieldMappingBuilder.buildForUpload(config, detailMissingValues);

            assertNull(result1.getExtraFields());
            assertNull(result2.getExtraFields());
        }
    }

    @Nested
    @DisplayName("buildForDownload")
    class BuildForDownloadTests {

        @Test
        @DisplayName("should return empty mapping when config is null")
        void shouldReturnEmptyMappingWhenConfigIsNull() {
            FieldMapping result = fieldMappingBuilder.buildForDownload(null);

            assertNotNull(result);
            assertNull(result.getConfig());
            assertNull(result.getTableFields());
        }

        @Test
        @DisplayName("should build mapping with table columns excluding ignore fields")
        void shouldBuildMappingWithTableColumnsExcludingIgnoreFields() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_DEFAULT");
            config.setTableName("test_table");
            config.setParserType("CSV");
            config.setIgnoreFields("ID,NAME");

            when(tableMetadataService.getTableColumns("DB_DEFAULT", "test_table"))
                    .thenReturn(List.of("ID", "NAME", "STATUS", "CREATED_AT"));
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(fileConverter.getDefaultConfig()).thenReturn(Map.of());

            FieldMapping result = fieldMappingBuilder.buildForDownload(config);

            assertEquals(2, result.getTableFields().size());
            assertTrue(result.getTableFields().contains("STATUS"));
            assertTrue(result.getTableFields().contains("CREATED_AT"));
            assertFalse(result.getTableFields().contains("ID"));
            assertFalse(result.getTableFields().contains("NAME"));
        }

        @Test
        @DisplayName("should not have extra fields for download scenario")
        void shouldNotHaveExtraFieldsForDownload() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_DEFAULT");
            config.setTableName("test_table");
            config.setParserType("CSV");

            when(tableMetadataService.getTableColumns("DB_DEFAULT", "test_table"))
                    .thenReturn(List.of("COL1"));
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(fileConverter.getDefaultConfig()).thenReturn(Map.of());

            FieldMapping result = fieldMappingBuilder.buildForDownload(config);

            assertNull(result.getExtraFields());
        }

        @Test
        @DisplayName("should apply parser config override")
        void shouldApplyParserConfigOverride() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_DEFAULT");
            config.setTableName("test_table");
            config.setParserType("CSV");
            config.setParserConfig("{\"delimiter\":\";\"}");

            when(tableMetadataService.getTableColumns("DB_DEFAULT", "test_table"))
                    .thenReturn(List.of("COL1"));
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            Map<String, String> defaultConfig = new java.util.LinkedHashMap<>();
            defaultConfig.put("charset", "UTF-8");
            defaultConfig.put("delimiter", ",");
            when(fileConverter.getDefaultConfig()).thenReturn(defaultConfig);

            FieldMapping result = fieldMappingBuilder.buildForDownload(config);

            assertNotNull(result.getConfig());
            assertTrue(result.getConfig().getParserConfig().contains("delimiter"));
        }
    }

    @Nested
    @DisplayName("FieldMapping.getValue")
    class FieldMappingGetValueTests {

        @Test
        @DisplayName("should return record value when present")
        void shouldReturnRecordValueWhenPresent() {
            FieldMapping mapping = new FieldMapping();
            mapping.setExtraFields(Map.of("FIELD1", "extra_value"));

            Map<String, Object> record = Map.of("FIELD1", "record_value", "FIELD2", "value2");

            Object result = mapping.getValue(record, "FIELD1");

            assertEquals("record_value", result);
        }

        @Test
        @DisplayName("should fall back to extra field when record value is null")
        void shouldFallBackToExtraFieldWhenRecordValueIsNull() {
            FieldMapping mapping = new FieldMapping();
            mapping.setExtraFields(Map.of("FIELD1", "extra_value"));

            Map<String, Object> record = new HashMap<>();
            record.put("FIELD1", null);

            Object result = mapping.getValue(record, "FIELD1");

            assertEquals("extra_value", result);
        }

        @Test
        @DisplayName("should return extra field when field not in record")
        void shouldReturnExtraFieldWhenFieldNotInRecord() {
            FieldMapping mapping = new FieldMapping();
            mapping.setExtraFields(Map.of("FIELD1", "extra_value"));

            Map<String, Object> record = Map.of("FIELD2", "value2");

            Object result = mapping.getValue(record, "FIELD1");

            assertEquals("extra_value", result);
        }
    }
}
