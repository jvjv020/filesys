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

@DisplayName("TxtConverter Tests")
class TxtConverterTest {

    private TxtConverter converter = new TxtConverter();

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
        @DisplayName("should parse pipe-delimited TXT")
        void shouldParsePipeDelimitedTxt() {
            String txt = "ID|NAME|AGE\n1|John|30\n2|Jane|25";
            InputStream input = new ByteArrayInputStream(txt.getBytes(StandardCharsets.UTF_8));
            FieldMapping mapping = createMapping(List.of("ID", "NAME", "AGE"));

            Iterator<List<Map<String, Object>>> iterator = converter.parse(input, mapping);
            List<Map<String, Object>> batch = iterator.next();

            assertEquals(2, batch.size());
            assertEquals("1", batch.get(0).get("ID"));
            assertEquals("John", batch.get(0).get("NAME"));
            assertEquals("30", batch.get(0).get("AGE"));
        }

        @Test
        @DisplayName("should parse TXT with custom separator")
        void shouldParseTxtWithCustomSeparator() {
            String txt = "ID,NAME,AGE\n1,John,30\n2,Jane,25";
            InputStream input = new ByteArrayInputStream(txt.getBytes(StandardCharsets.UTF_8));
            FieldMapping mapping = createMappingWithConfig(List.of("ID", "NAME", "AGE"), "{\"separator\":\",\"}");

            Iterator<List<Map<String, Object>>> iterator = converter.parse(input, mapping);
            List<Map<String, Object>> batch = iterator.next();

            assertEquals(2, batch.size());
            assertEquals("1", batch.get(0).get("ID"));
            assertEquals("John", batch.get(0).get("NAME"));
        }

        @Test
        @DisplayName("should handle empty TXT")
        void shouldHandleEmptyTxt() {
            String txt = "";
            InputStream input = new ByteArrayInputStream(txt.getBytes(StandardCharsets.UTF_8));
            FieldMapping mapping = createMapping(List.of("ID", "NAME", "AGE"));

            Iterator<List<Map<String, Object>>> iterator = converter.parse(input, mapping);

            assertFalse(iterator.hasNext());
        }
    }

    @Nested
    @DisplayName("generate")
    class GenerateTests {

        @Test
        @DisplayName("should generate pipe-delimited TXT from data")
        void shouldGeneratePipeDelimitedTxtFromData() {
            OutputStream output = new ByteArrayOutputStream();
            List<Map<String, Object>> records = List.of(
                Map.of("ID", "1", "NAME", "John", "AGE", "30"),
                Map.of("ID", "2", "NAME", "Jane", "AGE", "25")
            );
            Iterator<List<Map<String, Object>>> data = List.of(records).iterator();
            FieldMapping mapping = createMapping(List.of("ID", "NAME", "AGE"));

            converter.generate(output, data, mapping);

            String result = output.toString();
            assertTrue(result.contains("1|John|30"));
            assertTrue(result.contains("2|Jane|25"));
        }

        @Test
        @DisplayName("should generate TXT with custom separator")
        void shouldGenerateTxtWithCustomSeparator() {
            OutputStream output = new ByteArrayOutputStream();
            List<Map<String, Object>> records = List.of(
                Map.of("ID", "1", "NAME", "John")
            );
            Iterator<List<Map<String, Object>>> data = List.of(records).iterator();
            FieldMapping mapping = createMappingWithConfig(List.of("ID", "NAME"), "{\"separator\":\",\"}");

            converter.generate(output, data, mapping);

            String result = output.toString();
            assertTrue(result.contains("1,John"));
        }

        @Test
        @DisplayName("should handle null values in TXT generation")
        void shouldHandleNullValuesInTxtGeneration() {
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
            assertTrue(result.contains("1||30"));
        }
    }

    @Nested
    @DisplayName("getFormat")
    class GetFormatTests {

        @Test
        @DisplayName("should return TXT format")
        void shouldReturnTxtFormat() {
            assertEquals("TXT", converter.getFormat());
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
            assertEquals("|", config.get("separator"));
            assertEquals("delimiter", config.get("mode"));
            assertEquals("0", config.get("skipLines"));
        }
    }

    }
