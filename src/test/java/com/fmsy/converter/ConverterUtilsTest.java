package com.fmsy.converter;

import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConverterUtils Tests")
class ConverterUtilsTest {

    private FieldMapping createMappingWithConfig(String parserConfig) {
        FieldMapping mapping = new FieldMapping();
        TransferConfig config = new TransferConfig();
        config.setParserConfig(parserConfig);
        mapping.setConfig(config);
        return mapping;
    }

    @Nested
    @DisplayName("mergeConfig")
    class MergeConfigTests {

        @Test
        @DisplayName("should return default config when mapping is null")
        void shouldReturnDefaultConfigWhenMappingIsNull() {
            Map<String, String> defaultConfig = new HashMap<>();
            defaultConfig.put("encoding", "UTF-8");
            defaultConfig.put("separator", ",");

            Map<String, String> result = ConverterUtils.mergeConfig(defaultConfig, null);

            assertEquals(defaultConfig, result);
        }

        @Test
        @DisplayName("should merge mapping config with default config")
        void shouldMergeMappingConfigWithDefaultConfig() {
            Map<String, String> defaultConfig = new HashMap<>();
            defaultConfig.put("encoding", "UTF-8");
            defaultConfig.put("separator", ",");
            defaultConfig.put("header", "false");

            FieldMapping mapping = createMappingWithConfig("{\"separator\":\"|\",\"header\":true}");

            Map<String, String> result = ConverterUtils.mergeConfig(defaultConfig, mapping);

            assertEquals("UTF-8", result.get("encoding"));
            assertEquals("|", result.get("separator"));
            assertEquals("true", result.get("header"));
        }

        @Test
        @DisplayName("should override default values with mapping config")
        void shouldOverrideDefaultValuesWithMappingConfig() {
            Map<String, String> defaultConfig = new HashMap<>();
            defaultConfig.put("encoding", "UTF-8");
            defaultConfig.put("skipLines", "0");

            FieldMapping mapping = createMappingWithConfig("{\"encoding\":\"GBK\",\"skipLines\":\"2\"}");

            Map<String, String> result = ConverterUtils.mergeConfig(defaultConfig, mapping);

            assertEquals("GBK", result.get("encoding"));
            assertEquals("2", result.get("skipLines"));
        }
    }

    @Nested
    @DisplayName("resolveCharset")
    class ResolveCharsetTests {

        @Test
        @DisplayName("should resolve valid charset name")
        void shouldResolveValidCharsetName() {
            Charset charset = ConverterUtils.resolveCharset("UTF-8");

            assertEquals(StandardCharsets.UTF_8, charset);
        }

        @Test
        @DisplayName("should resolve GBK charset")
        void shouldResolveGbkCharset() {
            Charset charset = ConverterUtils.resolveCharset("GBK");

            assertEquals(Charset.forName("GBK"), charset);
        }

        @Test
        @DisplayName("should fallback to UTF-8 for invalid charset")
        void shouldFallbackToUtf8ForInvalidCharset() {
            Charset charset = ConverterUtils.resolveCharset("INVALID_CHARSET");

            assertEquals(StandardCharsets.UTF_8, charset);
        }

        @Test
        @DisplayName("should use custom default when provided")
        void shouldUseCustomDefaultWhenProvided() {
            Charset result = ConverterUtils.resolveCharset("INVALID", StandardCharsets.UTF_8);

            assertEquals(StandardCharsets.UTF_8, result);
        }
    }

    @Nested
    @DisplayName("parseInt")
    class ParseIntTests {

        @Test
        @DisplayName("should parse valid integer string")
        void shouldParseValidIntegerString() {
            assertEquals(100, ConverterUtils.parseInt("100", 0));
        }

        @Test
        @DisplayName("should return default for invalid integer string")
        void shouldReturnDefaultForInvalidIntegerString() {
            assertEquals(50, ConverterUtils.parseInt("invalid", 50));
        }

        @Test
        @DisplayName("should return default for null string")
        void shouldReturnDefaultForNullString() {
            assertEquals(10, ConverterUtils.parseInt(null, 10));
        }

        @Test
        @DisplayName("should parse negative number")
        void shouldParseNegativeNumber() {
            assertEquals(-5, ConverterUtils.parseInt("-5", 0));
        }
    }

    @Nested
    @DisplayName("parseJsonArray")
    class ParseJsonArrayTests {

        @Test
        @DisplayName("should parse simple JSON array")
        void shouldParseSimpleJsonArray() {
            String json = "[{\"name\":\"field1\",\"length\":10},{\"name\":\"field2\",\"length\":20}]";

            List<Map<String, String>> result = ConverterUtils.parseJsonArray(json);

            assertEquals(2, result.size());
            assertEquals("field1", result.get(0).get("name"));
            assertEquals("10", result.get(0).get("length"));
            assertEquals("field2", result.get(1).get("name"));
            assertEquals("20", result.get(1).get("length"));
        }

        @Test
        @DisplayName("should return empty list for null input")
        void shouldReturnEmptyListForNullInput() {
            List<Map<String, String>> result = ConverterUtils.parseJsonArray(null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty list for empty input")
        void shouldReturnEmptyListForEmptyInput() {
            List<Map<String, String>> result = ConverterUtils.parseJsonArray("");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty list for input without array")
        void shouldReturnEmptyListForInputWithoutArray() {
            List<Map<String, String>> result = ConverterUtils.parseJsonArray("{name: value}");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("unescapeValue")
    class UnescapeValueTests {

        @Test
        @DisplayName("should unescape tab character")
        void shouldUnescapeTabCharacter() {
            String result = ConverterUtils.unescapeValue("field1\\tfield2");

            assertEquals("field1\tfield2", result);
        }

        @Test
        @DisplayName("should unescape newline character")
        void shouldUnescapeNewlineCharacter() {
            String result = ConverterUtils.unescapeValue("line1\\nline2");

            assertEquals("line1\nline2", result);
        }

        @Test
        @DisplayName("should unescape carriage return character")
        void shouldUnescapeCarriageReturnCharacter() {
            String result = ConverterUtils.unescapeValue("line1\\rline2");

            assertEquals("line1\rline2", result);
        }

        @Test
        @DisplayName("should return original string when no escape sequences")
        void shouldReturnOriginalStringWhenNoEscapeSequences() {
            String original = "simple string";

            String result = ConverterUtils.unescapeValue(original);

            assertEquals(original, result);
        }

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(ConverterUtils.unescapeValue(null));
        }
    }
}
