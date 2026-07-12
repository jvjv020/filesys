package com.fmsy.converter;

import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CsvConverter Tests")
class CsvConverterTest {

    private CsvConverter converter = new CsvConverter();

    private FieldMapping createMapping(List<String> tableFields) {
        FieldMapping mapping = new FieldMapping();
        mapping.setTableFields(tableFields);
        return mapping;
    }

    private FieldMapping createMappingWithConfig(List<String> tableFields, String parserConfig) {
        FieldMapping mapping = new FieldMapping();
        mapping.setTableFields(tableFields);
        TransferConfig config = new TransferConfig();
        config.setParserConfig(parserConfig);
        mapping.setConfig(config);
        return mapping;
    }

    @Nested
    @DisplayName("parse")
    class ParseTests {

        @Test
        @DisplayName("should parse simple CSV with headers")
        void shouldParseSimpleCsvWithHeaders() {
            String csv = "ID,NAME,AGE\n1,John,30\n2,Jane,25";
            InputStream input = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
            FieldMapping mapping = createMappingWithConfig(List.of("ID", "NAME", "AGE"), "{\"header\":true}");

            Iterator<List<Map<String, Object>>> iterator = converter.parse(input, mapping);
            List<Map<String, Object>> batch = iterator.next();

            assertEquals(2, batch.size());
            assertEquals("1", batch.get(0).get("ID"));
            assertEquals("John", batch.get(0).get("NAME"));
            assertEquals("30", batch.get(0).get("AGE"));
            assertEquals("2", batch.get(1).get("ID"));
            assertEquals("Jane", batch.get(1).get("NAME"));
            assertEquals("25", batch.get(1).get("AGE"));
        }

        @Test
        @DisplayName("should parse CSV without header using mapping fields")
        void shouldParseCsvWithoutHeaderUsingMappingFields() {
            String csv = "1,John,30\n2,Jane,25";
            InputStream input = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
            FieldMapping mapping = createMapping(List.of("ID", "NAME", "AGE"));

            Iterator<List<Map<String, Object>>> iterator = converter.parse(input, mapping);
            List<Map<String, Object>> batch = iterator.next();

            assertEquals(2, batch.size());
            assertEquals("1", batch.get(0).get("ID"));
            assertEquals("John", batch.get(0).get("NAME"));
            assertEquals("30", batch.get(0).get("AGE"));
        }

        @Test
        @DisplayName("should handle empty CSV")
        void shouldHandleEmptyCsv() {
            String csv = "";
            InputStream input = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
            FieldMapping mapping = createMapping(List.of("ID", "NAME", "AGE"));

            Iterator<List<Map<String, Object>>> iterator = converter.parse(input, mapping);

            assertFalse(iterator.hasNext());
        }
    }

    @Nested
    @DisplayName("generate")
    class GenerateTests {

        @Test
        @DisplayName("should generate simple CSV from data")
        void shouldGenerateSimpleCsvFromData() {
            OutputStream output = new ByteArrayOutputStream();
            List<Map<String, Object>> records = List.of(
                Map.of("ID", "1", "NAME", "John", "AGE", "30"),
                Map.of("ID", "2", "NAME", "Jane", "AGE", "25")
            );
            Iterator<List<Map<String, Object>>> data = List.of(records).iterator();
            FieldMapping mapping = createMapping(List.of("ID", "NAME", "AGE"));

            converter.generate(output, data, mapping);

            String result = output.toString();
            assertTrue(result.contains("1,John,30"));
            assertTrue(result.contains("2,Jane,25"));
        }

        @Test
        @DisplayName("should generate CSV with header when configured")
        void shouldGenerateCsvWithHeaderWhenConfigured() {
            OutputStream output = new ByteArrayOutputStream();
            List<Map<String, Object>> records = List.of(
                Map.of("ID", "1", "NAME", "John")
            );
            Iterator<List<Map<String, Object>>> data = List.of(records).iterator();
            FieldMapping mapping = createMappingWithConfig(List.of("ID", "NAME"), "{\"header\":true}");

            converter.generate(output, data, mapping);

            String result = output.toString();
            assertTrue(result.startsWith("ID,NAME"));
            assertTrue(result.contains("1,John"));
        }

        @Test
        @DisplayName("should handle null values in CSV generation")
        void shouldHandleNullValuesInCsvGeneration() {
            OutputStream output = new ByteArrayOutputStream();
            Map<String, Object> record = new HashMap<>();
            record.put("ID", "1");
            record.put("NAME", null);
            record.put("AGE", "30");
            List<Map<String, Object>> records = List.of(record);
            Iterator<List<Map<String, Object>>> data = List.of(records).iterator();
            FieldMapping mapping = createMapping(List.of("ID", "NAME", "AGE"));

            converter.generate(output, data, mapping);

            String result = output.toString();
            assertTrue(result.contains("1,,30"));
        }
    }

    @Nested
    @DisplayName("getFormat")
    class GetFormatTests {

        @Test
        @DisplayName("should return CSV format")
        void shouldReturnCsvFormat() {
            assertEquals("CSV", converter.getFormat());
        }
    }

    @Nested
    @DisplayName("getDefaultConfig")
    class GetDefaultConfigTests {

        @Test
        @DisplayName("should return correct default config")
        void shouldReturnCorrectDefaultConfig() {
            Map<String, String> config = converter.getDefaultConfig();

            assertEquals("UTF-8", config.get("encoding"));
            assertEquals(",", config.get("separator"));
            assertEquals("\"", config.get("quote"));
            assertEquals("0", config.get("skipLines"));
            assertEquals("false", config.get("header"));
        }
    }

    @Nested
    @DisplayName("countRecords")
    class CountRecordsTests {

        @Test
        @DisplayName("should count records correctly with header")
        void shouldCountRecordsCorrectlyWithHeader() {
            String csv = "ID,NAME\n1,John\n2,Jane\n3,Bob";
            InputStream input = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
            FieldMapping mapping = createMappingWithConfig(List.of("ID", "NAME"), "{\"header\":true}");

            int count = converter.countRecords(input, mapping);

            assertEquals(3, count);
        }

        @Test
        @DisplayName("should count records correctly without header")
        void shouldCountRecordsCorrectlyWithoutHeader() {
            String csv = "1,John\n2,Jane\n3,Bob";
            InputStream input = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
            FieldMapping mapping = createMapping(List.of("ID", "NAME"));

            int count = converter.countRecords(input, mapping);

            assertEquals(3, count);
        }
    }
}
