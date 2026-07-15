package com.fmsy.converter;

import com.fmsy.model.FieldMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DbfConverter Tests")
class DbfConverterTest {

    private DbfConverter converter;
    private FieldMapping mapping;

    @BeforeEach
    void setUp() {
        converter = new DbfConverter();
        mapping = new FieldMapping();
        mapping.setTableFields(Arrays.asList("NAME", "AGE", "BIRTHDAY", "ACTIVE"));
        Map<String, String> config = new HashMap<>();
        config.put("encoding", "GBK");
        config.put("charLength", "15");
        config.put("fieldTypes", "{\"AGE\":\"N\",\"BIRTHDAY\":\"D\",\"ACTIVE\":\"L\"}");
        mapping.setConfig(new com.fmsy.model.TransferConfig());
        mapping.getConfig().setParserConfig(serializeFlatJson(config));
    }

    private String serializeFlatJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append('"').append(':')
                    .append('"').append(e.getValue()).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    @Nested
    @DisplayName("getFormat and getDefaultConfig")
    class FormatAndConfigTests {

        @Test
        @DisplayName("should return DBF format")
        void shouldReturnDbfFormat() {
            assertEquals("DBF", converter.getFormat());
        }

        @Test
        @DisplayName("should return default config with encoding and charLength")
        void shouldReturnDefaultConfig() {
            Map<String, String> config = converter.getDefaultConfig();
            assertEquals("GBK", config.get("encoding"));
            assertNotNull(config.get("charLength"));
        }
    }

    @Nested
    @DisplayName("generate - DBF binary output")
    class GenerateTests {

        @Test
        @DisplayName("should generate valid DBF with header and records")
        void shouldGenerateValidDbf() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            Map<String, Object> record1 = new LinkedHashMap<>();
            record1.put("NAME", "Alice");
            record1.put("AGE", 30L);
            record1.put("BIRTHDAY", LocalDate.of(1994, 5, 15));
            record1.put("ACTIVE", true);

            Map<String, Object> record2 = new LinkedHashMap<>();
            record2.put("NAME", "Bob");
            record2.put("AGE", 25L);
            record2.put("BIRTHDAY", LocalDate.of(1999, 3, 20));
            record2.put("ACTIVE", false);

            List<List<Map<String, Object>>> data = new ArrayList<>();
            data.add(Arrays.asList(record1, record2));

            converter.generate(output, data.iterator(), mapping);

            byte[] dbfBytes = output.toByteArray();
            assertTrue(dbfBytes.length > 0);
            // Verify header starts with 0x03 (DBASE III)
            assertEquals(0x03, dbfBytes[0] & 0xFF);
            // Verify EOF marker at the end
            assertTrue(dbfBytes[dbfBytes.length - 1] == 0x1A || dbfBytes[dbfBytes.length - 2] == 0x1A);
        }

        @Test
        @DisplayName("should write header, data records, and footer")
        void shouldWriteAllParts() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            converter.writeHeader(output, mapping, 1);

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("NAME", "Test");
            record.put("AGE", 20L);
            record.put("BIRTHDAY", LocalDate.of(2000, 1, 1));
            record.put("ACTIVE", true);

            List<List<Map<String, Object>>> data = new ArrayList<>();
            data.add(Collections.singletonList(record));

            int count = converter.writeDataRecords(output, data.iterator(), mapping);
            converter.writeFooter(output, mapping);

            assertEquals(1, count);
            byte[] dbfBytes = output.toByteArray();
            assertTrue(dbfBytes.length > 0);
        }

        @Test
        @DisplayName("should handle null values gracefully")
        void shouldHandleNullValues() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("NAME", null);
            record.put("AGE", null);
            record.put("BIRTHDAY", null);
            record.put("ACTIVE", null);

            List<List<Map<String, Object>>> data = new ArrayList<>();
            data.add(Collections.singletonList(record));

            int count = converter.writeDataRecords(output, data.iterator(), mapping);

            assertEquals(1, count);
            byte[] dbfBytes = output.toByteArray();
            assertTrue(dbfBytes.length > 0);
        }
    }

    @Nested
    @DisplayName("parse - DBF binary input")
    class ParseTests {

        @Test
        @DisplayName("should parse generated DBF file back to records")
        void shouldParseGeneratedDbf() throws IOException {
            // Must use writeHeader with correct record count + writeDataRecords + writeFooter
            // because generate() writes 0 as header record count, and the parser uses header
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("NAME", "Charlie");
            record.put("AGE", 35L);
            record.put("BIRTHDAY", LocalDate.of(1989, 8, 10));
            record.put("ACTIVE", true);

            converter.writeHeader(output, mapping, 1);
            System.out.println("DEBUG: after writeHeader, size=" + output.size());
            int written = converter.writeDataRecords(output,
                    java.util.Collections.singletonList(
                            java.util.Collections.singletonList(record)).iterator(), mapping);
            converter.writeFooter(output, mapping);
            assertEquals(1, written);

            byte[] dbfBytes = output.toByteArray();

            // Parse the generated DBF
            ByteArrayInputStream input = new ByteArrayInputStream(dbfBytes);
            System.out.println("DEBUG: DBF bytes length=" + dbfBytes.length + ", header[4-7]=" + (dbfBytes.length > 7 ? (dbfBytes[4] & 0xFF) + "," + (dbfBytes[5 & 0xFF]) + "," + (dbfBytes[6] & 0xFF) + "," + (dbfBytes[7] & 0xFF) : "n/a"));
            var parseIter = converter.parse(input, mapping);
            System.out.println("DEBUG: hasNext=" + parseIter.hasNext());

            assertTrue(parseIter.hasNext());
            List<Map<String, Object>> parsedBatch = parseIter.next();
            System.out.println("DEBUG: parsedBatch.size=" + parsedBatch.size());
            assertEquals(1, parsedBatch.size());

            Map<String, Object> parsedRecord = parsedBatch.get(0);
            assertEquals("Charlie", parsedRecord.get("NAME").toString().trim());
        }

        @Test
        @DisplayName("should handle empty DBF file")
        void shouldHandleEmptyDbf() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            converter.writeHeader(output, mapping, 0);
            converter.writeFooter(output, mapping);

            byte[] dbfBytes = output.toByteArray();
            ByteArrayInputStream input = new ByteArrayInputStream(dbfBytes);

            var parseIter = converter.parse(input, mapping);
            // When header says 0 records, iterator returns nothing
            assertFalse(parseIter.hasNext());
        }
    }

    }
