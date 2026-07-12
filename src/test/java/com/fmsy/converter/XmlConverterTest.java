package com.fmsy.converter;

import com.fmsy.model.FieldMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("XmlConverter Tests")
class XmlConverterTest {

    private XmlConverter converter;
    private FieldMapping mapping;

    @BeforeEach
    void setUp() {
        converter = new XmlConverter();
        mapping = new FieldMapping();
        mapping.setTableFields(Arrays.asList("NAME", "AGE", "CITY"));
        Map<String, String> config = new HashMap<>();
        config.put("encoding", "UTF-8");
        config.put("rootElement", "data");
        config.put("recordElement", "record");
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
        @DisplayName("should return XML format")
        void shouldReturnXmlFormat() {
            assertEquals("XML", converter.getFormat());
        }

        @Test
        @DisplayName("should return default config")
        void shouldReturnDefaultConfig() {
            Map<String, String> config = converter.getDefaultConfig();
            assertEquals("UTF-8", config.get("encoding"));
            assertEquals("data", config.get("rootElement"));
            assertEquals("record", config.get("recordElement"));
            assertEquals("false", config.get("fieldAsAttribute"));
        }
    }

    @Nested
    @DisplayName("generate - XML output")
    class GenerateTests {

        @Test
        @DisplayName("should generate valid XML with records")
        void shouldGenerateValidXml() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            Map<String, Object> record1 = new LinkedHashMap<>();
            record1.put("NAME", "Alice");
            record1.put("AGE", "30");
            record1.put("CITY", "Beijing");

            Map<String, Object> record2 = new LinkedHashMap<>();
            record2.put("NAME", "Bob");
            record2.put("AGE", "25");
            record2.put("CITY", "Shanghai");

            List<List<Map<String, Object>>> data = new ArrayList<>();
            data.add(Arrays.asList(record1, record2));

            converter.generate(output, data.iterator(), mapping);

            String xml = output.toString(StandardCharsets.UTF_8);
            assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
            assertTrue(xml.contains("<data>"));
            assertTrue(xml.contains("<record>"));
            assertTrue(xml.contains("<NAME>Alice</NAME>"));
            assertTrue(xml.contains("<AGE>30</AGE>"));
            assertTrue(xml.contains("<CITY>Beijing</CITY>"));
            assertTrue(xml.contains("</data>"));
        }

        @Test
        @DisplayName("should generate XML with header, records, footer")
        void shouldGenerateWithAllParts() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            converter.writeHeader(output, mapping, 0);

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("NAME", "Test");
            record.put("AGE", "20");
            record.put("CITY", "Shenzhen");

            List<List<Map<String, Object>>> data = new ArrayList<>();
            data.add(Collections.singletonList(record));

            int count = converter.writeDataRecords(output, data.iterator(), mapping);
            converter.writeFooter(output, mapping);

            assertEquals(1, count);
            String xml = output.toString(StandardCharsets.UTF_8);
            assertTrue(xml.contains("<NAME>Test</NAME>"));
            assertTrue(xml.contains("<AGE>20</AGE>"));
            assertTrue(xml.contains("<CITY>Shenzhen</CITY>"));
        }

        @Test
        @DisplayName("should handle null values in records")
        void shouldHandleNullValues() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("NAME", "Test");
            record.put("AGE", null);
            record.put("CITY", "Beijing");

            List<List<Map<String, Object>>> data = new ArrayList<>();
            data.add(Collections.singletonList(record));

            int count = converter.writeDataRecords(output, data.iterator(), mapping);

            assertEquals(1, count);
        }
    }

    @Nested
    @DisplayName("parse - XML input")
    class ParseTests {

        @Test
        @DisplayName("should parse XML with records")
        void shouldParseXmlWithRecords() {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<data>\n" +
                    "  <record>\n" +
                    "    <NAME>Alice</NAME>\n" +
                    "    <AGE>30</AGE>\n" +
                    "    <CITY>Beijing</CITY>\n" +
                    "  </record>\n" +
                    "  <record>\n" +
                    "    <NAME>Bob</NAME>\n" +
                    "    <AGE>25</AGE>\n" +
                    "    <CITY>Shanghai</CITY>\n" +
                    "  </record>\n" +
                    "</data>";

            ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            Iterator<List<Map<String, Object>>> iter = converter.parse(input, mapping);

            assertTrue(iter.hasNext());
            List<Map<String, Object>> batch = iter.next();
            assertEquals(2, batch.size());

            assertEquals("Alice", batch.get(0).get("NAME"));
            assertEquals("30", batch.get(0).get("AGE"));
            assertEquals("Beijing", batch.get(0).get("CITY"));

            assertEquals("Bob", batch.get(1).get("NAME"));
            assertEquals("25", batch.get(1).get("AGE"));
            assertEquals("Shanghai", batch.get(1).get("CITY"));
        }

        @Test
        @DisplayName("should parse XML with single record")
        void shouldParseSingleRecordXml() {
            String xml = "<?xml version=\"1.0\"?>\n<data>\n<record>\n<NAME>Charlie</NAME>\n</record>\n</data>";

            ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            Iterator<List<Map<String, Object>>> iter = converter.parse(input, mapping);

            assertTrue(iter.hasNext());
            List<Map<String, Object>> batch = iter.next();
            assertEquals(1, batch.size());
            assertEquals("Charlie", batch.get(0).get("NAME"));
        }

        @Test
        @DisplayName("should handle empty XML")
        void shouldHandleEmptyXml() {
            String xml = "<?xml version=\"1.0\"?>\n<data>\n</data>";

            ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            Iterator<List<Map<String, Object>>> iter = converter.parse(input, mapping);

            assertFalse(iter.hasNext());
        }

        @Test
        @DisplayName("should generate and then parse back")
        void shouldRoundTrip() throws IOException {
            // Generate
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("NAME", "RoundTrip");
            record.put("AGE", "42");
            record.put("CITY", "Guangzhou");

            List<List<Map<String, Object>>> data = new ArrayList<>();
            data.add(Collections.singletonList(record));

            converter.generate(output, data.iterator(), mapping);
            byte[] xmlBytes = output.toByteArray();

            // Parse back
            ByteArrayInputStream input = new ByteArrayInputStream(xmlBytes);
            Iterator<List<Map<String, Object>>> iter = converter.parse(input, mapping);

            assertTrue(iter.hasNext());
            List<Map<String, Object>> parsedBatch = iter.next();
            assertEquals(1, parsedBatch.size());
            assertEquals("RoundTrip", parsedBatch.get(0).get("NAME"));
            assertEquals("42", parsedBatch.get(0).get("AGE"));
        }

        @Test
        @DisplayName("should handle fieldAsAttribute mode in generate")
        void shouldHandleFieldAsAttributeMode() throws IOException {
            Map<String, String> attrConfig = new HashMap<>(converter.getDefaultConfig());
            attrConfig.put("fieldAsAttribute", "true");
            mapping.getConfig().setParserConfig(serializeFlatJson(attrConfig));

            ByteArrayOutputStream output = new ByteArrayOutputStream();

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("NAME", "AttrTest");
            record.put("AGE", "10");

            List<List<Map<String, Object>>> data = new ArrayList<>();
            data.add(Collections.singletonList(record));

            converter.writeHeader(output, mapping, 0);
            converter.writeDataRecords(output, data.iterator(), mapping);
            converter.writeFooter(output, mapping);

            String xml = output.toString(StandardCharsets.UTF_8);
            assertTrue(xml.contains("NAME=\"AttrTest\""));
            assertTrue(xml.contains("AGE=\"10\""));
        }
    }
}
