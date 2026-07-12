package com.fmsy.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FieldMapping Tests")
class FieldMappingTest {

    @Nested
    @DisplayName("field getter/setter")
    class FieldTests {

        @Test
        @DisplayName("should store and retrieve config")
        void shouldStoreAndRetrieveConfig() {
            FieldMapping mapping = new FieldMapping();
            TransferConfig config = new TransferConfig();
            config.setCategoryCode("CAT001");

            mapping.setConfig(config);

            assertNotNull(mapping.getConfig());
            assertEquals("CAT001", mapping.getConfig().getCategoryCode());
        }

        @Test
        @DisplayName("should store and retrieve tableFields")
        void shouldStoreAndRetrieveTableFields() {
            FieldMapping mapping = new FieldMapping();
            List<String> fields = List.of("ID", "NAME", "AGE");

            mapping.setTableFields(fields);

            assertEquals(3, mapping.getTableFields().size());
            assertEquals("ID", mapping.getTableFields().get(0));
        }

        @Test
        @DisplayName("should store and retrieve extraFields")
        void shouldStoreAndRetrieveExtraFields() {
            FieldMapping mapping = new FieldMapping();
            Map<String, String> extra = new HashMap<>();
            extra.put("STATUS", "ACTIVE");
            extra.put("TYPE", "NORMAL");

            mapping.setExtraFields(extra);

            assertEquals(2, mapping.getExtraFields().size());
            assertEquals("ACTIVE", mapping.getExtraFields().get("STATUS"));
        }
    }

    @Nested
    @DisplayName("getValue")
    class GetValueTests {

        @Test
        @DisplayName("should return record value when present")
        void shouldReturnRecordValueWhenPresent() {
            FieldMapping mapping = new FieldMapping();
            Map<String, Object> record = new HashMap<>();
            record.put("NAME", "John");
            record.put("AGE", 30);

            Object value = mapping.getValue(record, "NAME");

            assertEquals("John", value);
        }

        @Test
        @DisplayName("should return extraField value when record value not present")
        void shouldReturnExtraFieldWhenRecordValueNotPresent() {
            FieldMapping mapping = new FieldMapping();
            Map<String, Object> record = new HashMap<>();
            record.put("NAME", "John");
            Map<String, String> extraFields = new HashMap<>();
            extraFields.put("STATUS", "ACTIVE");
            extraFields.put("TYPE", "NORMAL");
            mapping.setExtraFields(extraFields);

            Object statusValue = mapping.getValue(record, "STATUS");
            Object typeValue = mapping.getValue(record, "TYPE");

            assertEquals("ACTIVE", statusValue);
            assertEquals("NORMAL", typeValue);
        }

        @Test
        @DisplayName("should prioritize record over extraFields")
        void shouldPrioritizeRecordOverExtraFields() {
            FieldMapping mapping = new FieldMapping();
            Map<String, Object> record = new HashMap<>();
            record.put("CODE", "ABC123");
            Map<String, String> extraFields = new HashMap<>();
            extraFields.put("CODE", "DEFAULT");
            mapping.setExtraFields(extraFields);

            Object value = mapping.getValue(record, "CODE");

            assertEquals("ABC123", value);
        }

        @Test
        @DisplayName("should return null when value not found in record or extraFields")
        void shouldReturnNullWhenNotFound() {
            FieldMapping mapping = new FieldMapping();
            Map<String, Object> record = new HashMap<>();
            record.put("NAME", "John");
            mapping.setExtraFields(Map.of("STATUS", "ACTIVE"));

            Object value = mapping.getValue(record, "NONEXISTENT");

            assertNull(value);
        }

        @Test
        @DisplayName("should handle null extraFields gracefully")
        void shouldHandleNullExtraFieldsGracefully() {
            FieldMapping mapping = new FieldMapping();
            Map<String, Object> record = new HashMap<>();
            record.put("NAME", "John");
            mapping.setExtraFields(null);

            Object value = mapping.getValue(record, "NAME");

            assertEquals("John", value);
        }

        @Test
        @DisplayName("should return null when both record and extraFields are empty")
        void shouldReturnNullWhenBothEmpty() {
            FieldMapping mapping = new FieldMapping();
            Map<String, Object> record = new HashMap<>();
            mapping.setExtraFields(Map.of());

            Object value = mapping.getValue(record, "FIELD");

            assertNull(value);
        }

        @Test
        @DisplayName("should handle integer values from record")
        void shouldHandleIntegerValues() {
            FieldMapping mapping = new FieldMapping();
            Map<String, Object> record = new HashMap<>();
            record.put("AGE", 25);
            record.put("SCORE", 100);

            assertEquals(25, mapping.getValue(record, "AGE"));
            assertEquals(100, mapping.getValue(record, "SCORE"));
        }

        @Test
        @DisplayName("should handle null values in record")
        void shouldHandleNullValuesInRecord() {
            FieldMapping mapping = new FieldMapping();
            Map<String, Object> record = new HashMap<>();
            record.put("NAME", null);

            Object value = mapping.getValue(record, "NAME");

            assertNull(value);
        }
    }
}
