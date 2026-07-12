package com.fmsy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FilePathUtils Tests")
class FilePathUtilsTest {

    @Nested
    @DisplayName("extractDirectory() method")
    class ExtractDirectoryTests {

        @Test
        @DisplayName("should extract directory from Unix path")
        void shouldExtractDirectoryFromUnixPath() {
            String result = FilePathUtils.extractDirectory("/home/user/files/data.txt");
            assertEquals("/home/user/files", result);
        }

        @Test
        @DisplayName("should extract directory from Windows path")
        void shouldExtractDirectoryFromWindowsPath() {
            String result = FilePathUtils.extractDirectory("D:\\project\\files\\data.txt");
            assertEquals("D:\\project\\files", result);
        }

        @Test
        @DisplayName("should return dot for file without directory")
        void shouldReturnDotForFileWithoutDirectory() {
            assertEquals(".", FilePathUtils.extractDirectory("file.txt"));
        }

        @Test
        @DisplayName("should handle path with forward slash at root")
        void shouldHandlePathWithForwardSlashAtRoot() {
            assertEquals(".", FilePathUtils.extractDirectory("/file.txt"));
        }
    }

    @Nested
    @DisplayName("extractFileName() method")
    class ExtractFileNameTests {

        @Test
        @DisplayName("should extract filename from Unix path")
        void shouldExtractFileNameFromUnixPath() {
            String result = FilePathUtils.extractFileName("/home/user/files/data.txt");
            assertEquals("data.txt", result);
        }

        @Test
        @DisplayName("should extract filename from Windows path")
        void shouldExtractFileNameFromWindowsPath() {
            String result = FilePathUtils.extractFileName("D:\\project\\files\\data.txt");
            assertEquals("data.txt", result);
        }

        @Test
        @DisplayName("should return full path when no slash")
        void shouldReturnFullPathWhenNoSlash() {
            assertEquals("file.txt", FilePathUtils.extractFileName("file.txt"));
        }

        @Test
        @DisplayName("should handle filename at root")
        void shouldHandleFileNameAtRoot() {
            assertEquals("file.txt", FilePathUtils.extractFileName("/file.txt"));
        }
    }

    @Nested
    @DisplayName("extractParentDirectory() method")
    class ExtractParentDirectoryTests {

        @Test
        @DisplayName("should extract parent directory from Unix path")
        void shouldExtractParentDirectoryFromUnixPath() {
            String result = FilePathUtils.extractParentDirectory("/home/user/files/data.txt");
            assertEquals("/home/user/files", result);
        }

        @Test
        @DisplayName("should extract parent directory from Windows path")
        void shouldExtractParentDirectoryFromWindowsPath() {
            String result = FilePathUtils.extractParentDirectory("D:\\project\\files\\data.txt");
            assertEquals("D:\\project\\files", result);
        }

        @Test
        @DisplayName("should return null for file at root")
        void shouldReturnNullForFileAtRoot() {
            assertNull(FilePathUtils.extractParentDirectory("/file.txt"));
        }

        @Test
        @DisplayName("should return null for file without directory")
        void shouldReturnNullForFileWithoutDirectory() {
            assertNull(FilePathUtils.extractParentDirectory("file.txt"));
        }
    }

    @Nested
    @DisplayName("path validation")
    class PathValidationTests {

        @Test
        @DisplayName("should throw IllegalArgumentException for null path")
        void shouldThrowForNullPath() {
            assertThrows(IllegalArgumentException.class, () -> {
                FilePathUtils.extractDirectory(null);
            });
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for path traversal")
        void shouldThrowForPathTraversal() {
            assertThrows(IllegalArgumentException.class, () -> {
                FilePathUtils.extractDirectory("/home/user/../../../etc/passwd");
            });
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for Windows path traversal")
        void shouldThrowForWindowsPathTraversal() {
            assertThrows(IllegalArgumentException.class, () -> {
                FilePathUtils.extractDirectory("D:\\project\\..\\..\\windows\\system32");
            });
        }

        @ParameterizedTest
        @ValueSource(strings = {"..", "../file", "file/..", "a/b/../c/.."})
        @DisplayName("should reject various path traversal patterns")
        void shouldRejectPathTraversalPatterns(String path) {
            assertThrows(IllegalArgumentException.class, () -> {
                FilePathUtils.extractFileName(path);
            });
        }
    }
}
