package com.fmsy.transfer.placeholder;

import com.fmsy.util.ResolvedPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PlaceholderResolver Tests")
class PlaceholderResolverTest {

    private final PlaceholderResolver resolver = new PlaceholderResolver();

    private Map<String, String> context(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Nested
    @DisplayName("Time Placeholders")
    class TimePlaceholderTests {

        @Test
        @DisplayName("should resolve YYYYMMDD to current date")
        void shouldResolveYyyymmdd() {
            String result = resolver.resolve("{YYYYMMDD}", Map.of());
            String expected = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            assertEquals(expected, result);
        }

        @Test
        @DisplayName("should resolve YYYYMMDDHHmmss to full datetime")
        void shouldResolveYyyymmddhhmmss() {
            String result = resolver.resolve("{YYYYMMDDHHmmss}", Map.of());
            // Format: yyyyMMddHHmmss (14 characters)
            assertEquals(14, result.length());
            assertTrue(result.matches("\\d{14}"));
        }

        @Test
        @DisplayName("should resolve date placeholder to current date")
        void shouldResolveDatePlaceholder() {
            String result = resolver.resolve("{date}", Map.of());
            String expected = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            assertEquals(expected, result);
        }

        @Test
        @DisplayName("should resolve time placeholder")
        void shouldResolveTimePlaceholder() {
            String result = resolver.resolve("{time}", Map.of());
            // Format: HHmmss
            assertTrue(result.matches("\\d{6}"));
        }

        @Test
        @DisplayName("should resolve now placeholder to full datetime")
        void shouldResolveNowPlaceholder() {
            String result = resolver.resolve("{now}", Map.of());
            assertEquals(14, result.length());
            assertTrue(result.matches("\\d{14}"));
        }
    }

    @Nested
    @DisplayName("Context Placeholders")
    class ContextPlaceholderTests {

        @Test
        @DisplayName("should resolve FIELD_NAME from context")
        void shouldResolveFieldNameFromContext() {
            Map<String, String> ctx = context("FIELD_NAME", "value123");
            String result = resolver.resolve("{FIELD_NAME}", ctx);
            assertEquals("value123", result);
        }

        @Test
        @DisplayName("should resolve EXTRA_INFO from context")
        void shouldResolveExtraInfoFromContext() {
            Map<String, String> ctx = context("EXTRA_INFO", "extra_value");
            String result = resolver.resolve("{EXTRA_INFO}", ctx);
            assertEquals("extra_value", result);
        }

        @Test
        @DisplayName("should preserve prefix and suffix around placeholder")
        void shouldPreservePrefixAndSuffix() {
            Map<String, String> ctx = context("FIELD_NAME", "center");
            String result = resolver.resolve("prefix_{FIELD_NAME}_suffix", ctx);
            assertEquals("prefix_center_suffix", result);
        }

        @Test
        @DisplayName("should keep unknown placeholder when not in context")
        void shouldKeepUnknownPlaceholderWhenNotInContext() {
            Map<String, String> ctx = context("OTHER", "value");
            String result = resolver.resolve("{UNKNOWN}", ctx);
            assertEquals("{UNKNOWN}", result);
        }

        @Test
        @DisplayName("should resolve multiple different placeholders")
        void shouldResolveMultiplePlaceholders() {
            Map<String, String> ctx = context(
                    "FIELD_A", "aaa",
                    "FIELD_B", "bbb"
            );
            String result = resolver.resolve("{FIELD_A}_{FIELD_B}", ctx);
            assertEquals("aaa_bbb", result);
        }

        @Test
        @DisplayName("should handle empty context gracefully")
        void shouldHandleEmptyContext() {
            String result = resolver.resolve("{YYYYMMDD}", Map.of());
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("File Info Placeholders")
    class FileInfoPlaceholderTests {

        @Test
        @DisplayName("should resolve stem from ResolvedPath")
        void shouldResolveStem() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            String result = resolver.resolve("{stem}", Map.of(), fileInfo);
            assertEquals("file", result);
        }

        @Test
        @DisplayName("should resolve name from ResolvedPath")
        void shouldResolveName() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            String result = resolver.resolve("{name}", Map.of(), fileInfo);
            assertEquals("file.csv", result);
        }

        @Test
        @DisplayName("should resolve ext from ResolvedPath")
        void shouldResolveExt() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            String result = resolver.resolve("{ext}", Map.of(), fileInfo);
            assertEquals(".csv", result);
        }

        @Test
        @DisplayName("should resolve dir from ResolvedPath")
        void shouldResolveDir() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            String result = resolver.resolve("{dir}", Map.of(), fileInfo);
            assertEquals("/data/export", result);
        }

        @Test
        @DisplayName("should resolve dn (last dir segment) from ResolvedPath")
        void shouldResolveDn() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/export/BR001/file.csv");
            String result = resolver.resolve("{dn}", Map.of(), fileInfo);
            assertEquals("BR001", result);
        }

        @Test
        @DisplayName("should resolve up (parent dir) from ResolvedPath")
        void shouldResolveUp() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/export/BR001/file.csv");
            String result = resolver.resolve("{up}", Map.of(), fileInfo);
            assertEquals("/data/export", result);
        }

        @Test
        @DisplayName("should combine file info and context placeholders")
        void shouldCombineFileInfoAndContext() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            Map<String, String> ctx = context("FIELD_NAME", "value");
            String result = resolver.resolve("{stem}_{FIELD_NAME}", ctx, fileInfo);
            assertEquals("file_value", result);
        }

        @Test
        @DisplayName("should handle null fileInfo gracefully")
        void shouldHandleNullFileInfo() {
            String result = resolver.resolve("{stem}", Map.of(), null);
            assertEquals("{stem}", result);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should return null when template is null")
        void shouldReturnNullWhenTemplateIsNull() {
            String result = resolver.resolve(null, Map.of());
            assertNull(result);
        }

        @Test
        @DisplayName("should return empty string when template is empty")
        void shouldReturnEmptyWhenTemplateIsEmpty() {
            String result = resolver.resolve("", Map.of());
            assertEquals("", result);
        }

        @Test
        @DisplayName("should preserve text without placeholders")
        void shouldPreserveTextWithoutPlaceholders() {
            String result = resolver.resolve("no_placeholders_here", Map.of());
            assertEquals("no_placeholders_here", result);
        }

        @Test
        @DisplayName("should resolve time keyword even if appears in context")
        void shouldNotOverrideTimeKeywords() {
            // Time keywords should not be overridden by context values
            Map<String, String> ctx = context("YYYYMMDD", "should_not_appear");
            String result = resolver.resolve("{YYYYMMDD}", ctx);
            String expected = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            assertEquals(expected, result);
        }
    }
}
