package com.fmsy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ParserConfigUtil Tests")
class ParserConfigUtilTest {

    @Nested
    @DisplayName("parseJson() method")
    class ParseJsonTests {

        @Test
        @DisplayName("should parse simple JSON object")
        void shouldParseSimpleJsonObject() {
            String json = "{\"key1\":\"value1\",\"key2\":\"value2\"}";
            Map<String, String> result = ParserConfigUtil.parseJson(json);
            assertEquals(2, result.size());
            assertEquals("value1", result.get("key1"));
            assertEquals("value2", result.get("key2"));
        }

        @Test
        @DisplayName("should return empty map for null input")
        void shouldReturnEmptyMapForNull() {
            Map<String, String> result = ParserConfigUtil.parseJson(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty map for empty string")
        void shouldReturnEmptyMapForEmptyString() {
            Map<String, String> result = ParserConfigUtil.parseJson("");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty map for whitespace only")
        void shouldReturnEmptyMapForWhitespace() {
            Map<String, String> result = ParserConfigUtil.parseJson("   ");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should handle JSON without outer braces")
        void shouldHandleJsonWithoutOuterBraces() {
            String json = "\"key1\":\"value1\",\"key2\":\"value2\"}";
            Map<String, String> result = ParserConfigUtil.parseJson(json);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("should parse numeric values")
        void shouldParseNumericValues() {
            String json = "{\"intKey\":123,\"floatKey\":45.67}";
            Map<String, String> result = ParserConfigUtil.parseJson(json);
            assertEquals("123", result.get("intKey"));
            assertEquals("45.67", result.get("floatKey"));
        }

        @Test
        @DisplayName("should parse boolean values")
        void shouldParseBooleanValues() {
            String json = "{\"boolTrue\":true,\"boolFalse\":false}";
            Map<String, String> result = ParserConfigUtil.parseJson(json);
            assertEquals("true", result.get("boolTrue"));
            assertEquals("false", result.get("boolFalse"));
        }

        @Test
        @DisplayName("should parse null value")
        void shouldParseNullValue() {
            String json = "{\"nullKey\":null}";
            Map<String, String> result = ParserConfigUtil.parseJson(json);
            assertEquals("null", result.get("nullKey"));
        }

        @Test
        @DisplayName("should handle escaped characters")
        void shouldHandleEscapedCharacters() {
            String json = "{\"newLine\":\"line1\\nline2\",\"tab\":\"col1\\tcol2\"}";
            Map<String, String> result = ParserConfigUtil.parseJson(json);
            assertEquals("line1\nline2", result.get("newLine"));
            assertEquals("col1\tcol2", result.get("tab"));
        }

        @Test
        @DisplayName("should handle escaped quotes")
        void shouldHandleEscapedQuotes() {
            String json = "{\"quote\":\"say \\\"hello\\\"\"}";
            Map<String, String> result = ParserConfigUtil.parseJson(json);
            assertEquals("say \"hello\"", result.get("quote"));
        }

        @Test
        @DisplayName("should handle whitespace between elements")
        void shouldHandleWhitespaceBetweenElements() {
            String json = "{\n  \"key1\" : \"value1\",\n  \"key2\" : \"value2\"\n}";
            Map<String, String> result = ParserConfigUtil.parseJson(json);
            assertEquals(2, result.size());
            assertEquals("value1", result.get("key1"));
            assertEquals("value2", result.get("key2"));
        }

        @Test
        @DisplayName("should preserve insertion order")
        void shouldPreserveInsertionOrder() {
            String json = "{\"first\":\"1\",\"second\":\"2\",\"third\":\"3\"}";
            Map<String, String> result = ParserConfigUtil.parseJson(json);
            String[] keys = result.keySet().toArray(new String[0]);
            assertEquals("first", keys[0]);
            assertEquals("second", keys[1]);
            assertEquals("third", keys[2]);
        }
    }
}
