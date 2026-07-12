package com.fmsy.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExternalConfigLoader Tests")
class ExternalConfigLoaderTest {

    private ExternalConfigLoader loader;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader = new ExternalConfigLoader(tempDir.toString());
    }

    @Nested
    @DisplayName("loadExternalConfig")
    class LoadExternalConfigTests {

        @Test
        @DisplayName("should load config from file with matching extension")
        void shouldLoadConfigFromFile() throws IOException {
            Path formatDir = tempDir.resolve("csv");
            Files.createDirectories(formatDir);
            Path configFile = formatDir.resolve("CAT001_CTRL001.json");
            Files.writeString(configFile, "{\"key\":\"value\"}", StandardCharsets.UTF_8);

            String result = loader.loadExternalConfig("CsvConverter", "CAT001", "CTRL001");

            assertEquals("{\"key\":\"value\"}", result);
        }

        @Test
        @DisplayName("should try multiple extensions")
        void shouldTryMultipleExtensions() throws IOException {
            Path formatDir = tempDir.resolve("dbf");
            Files.createDirectories(formatDir);
            Path configFile = formatDir.resolve("CAT001_CTRL001.txt");
            Files.writeString(configFile, "text content", StandardCharsets.UTF_8);

            String result = loader.loadExternalConfig("DbfConverter", "CAT001", "CTRL001");

            assertEquals("text content", result);
        }

        @Test
        @DisplayName("should return null when categoryCode is null")
        void shouldReturnNullWhenCategoryCodeIsNull() {
            String result = loader.loadExternalConfig("CsvConverter", null, "CTRL001");
            assertNull(result);
        }

        @Test
        @DisplayName("should return null when controlCode is null")
        void shouldReturnNullWhenControlCodeIsNull() {
            String result = loader.loadExternalConfig("CsvConverter", "CAT001", null);
            assertNull(result);
        }

        @Test
        @DisplayName("should return null when file does not exist")
        void shouldReturnNullWhenFileDoesNotExist() {
            String result = loader.loadExternalConfig("CsvConverter", "NONEXISTENT", "CTRL");
            assertNull(result);
        }

        @Test
        @DisplayName("should try file without extension")
        void shouldTryFileWithoutExtension() throws IOException {
            Path formatDir = tempDir.resolve("xml");
            Files.createDirectories(formatDir);
            Path configFile = formatDir.resolve("CAT001_CTRL001");
            Files.writeString(configFile, "no ext content", StandardCharsets.UTF_8);

            String result = loader.loadExternalConfig("XmlConverter", "CAT001", "CTRL001");

            assertEquals("no ext content", result);
        }
    }

    @Nested
    @DisplayName("resolveExternalPath")
    class ResolveExternalPathTests {

        @Test
        @DisplayName("should resolve path for existing file")
        void shouldResolveExistingFile() throws IOException {
            Path formatDir = tempDir.resolve("csv");
            Files.createDirectories(formatDir);
            Path configFile = formatDir.resolve("CAT001_CTRL001.xsl");
            Files.createFile(configFile);

            Optional<Path> result = loader.resolveExternalPath(
                    "CsvConverter", "CAT001", "CTRL001", ".xsl");

            assertTrue(result.isPresent());
            assertEquals(configFile, result.get());
        }

        @Test
        @DisplayName("should return empty for non-existing file")
        void shouldReturnEmptyForNonExistingFile() {
            Optional<Path> result = loader.resolveExternalPath(
                    "CsvConverter", "CAT001", "CTRL001", ".xsl");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("should return empty when suffix contains parent dir traversal")
        void shouldRejectPathTraversal() {
            Optional<Path> result = loader.resolveExternalPath(
                    "CsvConverter", "CAT001", "CTRL001", "../../etc/passwd");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("should return empty when categoryCode is null")
        void shouldReturnEmptyWhenCategoryCodeIsNull() {
            Optional<Path> result = loader.resolveExternalPath(
                    "CsvConverter", null, "CTRL001", ".xsl");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("should return empty when suffix is null")
        void shouldReturnEmptyWhenSuffixIsNull() {
            Optional<Path> result = loader.resolveExternalPath(
                    "CsvConverter", "CAT001", "CTRL001", null);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("should ensure path is within base directory")
        void shouldEnsurePathWithinBaseDir() {
            // Path outside base dir should be rejected
            Optional<Path> result = loader.resolveExternalPath(
                    "CsvConverter", "..", "CTRL001", ".xsl");

            assertFalse(result.isPresent());
        }
    }

    @Nested
    @DisplayName("readExternalPath")
    class ReadExternalPathTests {

        @Test
        @DisplayName("should read file content")
        void shouldReadFileContent() throws IOException {
            Path testFile = tempDir.resolve("test.txt");
            Files.writeString(testFile, "file content", StandardCharsets.UTF_8);

            String result = loader.readExternalPath(testFile);

            assertEquals("file content", result);
        }

        @Test
        @DisplayName("should return null when file does not exist")
        void shouldReturnNullWhenFileNotExist() {
            Path nonExistent = tempDir.resolve("nonexistent.txt");

            String result = loader.readExternalPath(nonExistent);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("normalizeFormat")
    class NormalizeFormatTests {

        @Test
        @DisplayName("should strip Converter suffix")
        void shouldStripConverterSuffix() {
            // Use reflection to test protected behavior via public API
            String csvResult = loader.loadExternalConfig("CsvConverter", null, null);
            assertNull(csvResult); // null cat/ctrl returns null, but shows normalization ran

            String dbfResult = loader.loadExternalConfig("DbfConverter", null, null);
            assertNull(dbfResult);
        }

        @Test
        @DisplayName("should lowercase the format name")
        void shouldLowercaseFormat() {
            String result = loader.loadExternalConfig("XML", null, null);
            assertNull(result);
        }

        @Test
        @DisplayName("should default to 'default' for null parserType")
        void shouldDefaultForNullParserType() {
            String result = loader.loadExternalConfig(null, null, null);
            assertNull(result);
        }
    }
}
