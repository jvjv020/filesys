package com.fmsy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResolvedPath Tests")
class ResolvedPathTest {

    @Nested
    @DisplayName("of() with valid paths")
    class OfWithValidPathsTests {

        @Test
        @DisplayName("should parse full absolute path")
        void shouldParseFullAbsolutePath() {
            ResolvedPath path = ResolvedPath.of("/data/export/BR001/data_20260615.csv");

            assertNotNull(path);
            assertEquals("/data/export/BR001/data_20260615.csv", path.fullPath());
            assertEquals("data_20260615", path.stem());
            assertEquals("data_20260615.csv", path.name());
            assertEquals(".csv", path.ext());
            assertEquals("/data/export/BR001", path.dir());
            assertEquals("BR001", path.dn());
            assertEquals("/data/export", path.up());
        }

        @Test
        @DisplayName("should parse file in root directory")
        void shouldParseRootDirectoryFile() {
            ResolvedPath path = ResolvedPath.of("/file.txt");

            assertNotNull(path);
            assertEquals("/file.txt", path.fullPath());
            assertEquals("file", path.stem());
            assertEquals("file.txt", path.name());
            assertEquals(".txt", path.ext());
            assertEquals("", path.dir());
            assertEquals("", path.dn());
            assertEquals("", path.up());
        }

        @Test
        @DisplayName("should parse file without extension")
        void shouldParseFileWithoutExtension() {
            ResolvedPath path = ResolvedPath.of("/data/README");

            assertNotNull(path);
            assertEquals("/data/README", path.fullPath());
            assertEquals("README", path.stem());
            assertEquals("README", path.name());
            assertEquals("", path.ext());
            assertEquals("/data", path.dir());
            assertEquals("data", path.dn());
            assertEquals("", path.up());
        }

        @Test
        @DisplayName("should parse file with multiple dots")
        void shouldParseFileWithMultipleDots() {
            ResolvedPath path = ResolvedPath.of("/data/backup/2024.01.15.log");

            assertNotNull(path);
            assertEquals("/data/backup/2024.01.15.log", path.fullPath());
            assertEquals("2024.01.15", path.stem());
            assertEquals("2024.01.15.log", path.name());
            assertEquals(".log", path.ext());
            assertEquals("/data/backup", path.dir());
            assertEquals("backup", path.dn());
            assertEquals("/data", path.up());
        }

        @Test
        @DisplayName("should parse relative path")
        void shouldParseRelativePath() {
            ResolvedPath path = ResolvedPath.of("data/file.csv");

            assertNotNull(path);
            assertEquals("data/file.csv", path.fullPath());
            assertEquals("file", path.stem());
            assertEquals("file.csv", path.name());
            assertEquals(".csv", path.ext());
            assertEquals("data", path.dir());
            assertEquals("data", path.dn());
            assertEquals("", path.up());
        }

        @Test
        @DisplayName("should parse single file name")
        void shouldParseSingleFileName() {
            ResolvedPath path = ResolvedPath.of("readme.md");

            assertNotNull(path);
            assertEquals("readme.md", path.fullPath());
            assertEquals("readme", path.stem());
            assertEquals("readme.md", path.name());
            assertEquals(".md", path.ext());
            assertEquals("", path.dir());
            assertEquals("", path.dn());
            assertEquals("", path.up());
        }

        @Test
        @DisplayName("should parse deeply nested path")
        void shouldParseDeeplyNestedPath() {
            ResolvedPath path = ResolvedPath.of("/a/b/c/d/e/f/g.txt");

            assertNotNull(path);
            assertEquals("/a/b/c/d/e/f/g.txt", path.fullPath());
            assertEquals("g", path.stem());
            assertEquals("g.txt", path.name());
            assertEquals(".txt", path.ext());
            assertEquals("/a/b/c/d/e/f", path.dir());
            assertEquals("f", path.dn());
            assertEquals("/a/b/c/d/e", path.up());
        }

        @Test
        @DisplayName("should handle dotfile without extension")
        void shouldHandleDotfile() {
            ResolvedPath path = ResolvedPath.of("/home/user/.bashrc");

            assertNotNull(path);
            assertEquals("/home/user/.bashrc", path.fullPath());
            assertEquals(".bashrc", path.stem());
            assertEquals(".bashrc", path.name());
            assertEquals("", path.ext());
            assertEquals("/home/user", path.dir());
            assertEquals("user", path.dn());
            assertEquals("/home", path.up());
        }

        @Test
        @DisplayName("should handle file with extension but no dir")
        void shouldHandleFileWithExtNoDir() {
            ResolvedPath path = ResolvedPath.of("notes.txt");

            assertNotNull(path);
            assertEquals("notes.txt", path.fullPath());
            assertEquals("notes", path.stem());
            assertEquals("notes.txt", path.name());
            assertEquals(".txt", path.ext());
            assertEquals("", path.dir());
            assertEquals("", path.dn());
            assertEquals("", path.up());
        }

        @Test
        @DisplayName("should handle trailing slash")
        void shouldHandleTrailingSlash() {
            ResolvedPath path = ResolvedPath.of("/data/dir/");

            assertNotNull(path);
            assertEquals("/data/dir/", path.fullPath());
            assertEquals("", path.stem());
            assertEquals("", path.name());
            assertEquals("", path.ext());
            assertEquals("/data/dir", path.dir());
            assertEquals("dir", path.dn());
            assertEquals("/data", path.up());
        }
    }

    @Nested
    @DisplayName("of() with edge cases")
    class OfEdgeCaseTests {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(ResolvedPath.of(null));
        }

        @Test
        @DisplayName("should return null for empty string")
        void shouldReturnNullForEmpty() {
            assertNull(ResolvedPath.of(""));
        }

        @Test
        @DisplayName("should handle path with only slash")
        void shouldHandleRootOnly() {
            ResolvedPath path = ResolvedPath.of("/");

            assertNotNull(path);
            assertEquals("/", path.fullPath());
            assertEquals("", path.dir());
        }
    }

    @Nested
    @DisplayName("resolveRelative")
    class ResolveRelativeTests {

        @Test
        @DisplayName("should return absolute path unchanged")
        void shouldReturnAbsoluteUnchanged() {
            ResolvedPath path = ResolvedPath.of("/data/export/file.csv");

            String result = path.resolveRelative("/other/path/file.txt");
            assertEquals("/other/path/file.txt", result);
        }

        @Test
        @DisplayName("should prepend dir to relative path")
        void shouldPrependDirToRelative() {
            ResolvedPath path = ResolvedPath.of("/data/export/file.csv");

            String result = path.resolveRelative("subdir/output.txt");
            assertEquals("/data/export/subdir/output.txt", result);
        }

        @Test
        @DisplayName("should return null for null pattern")
        void shouldReturnNullForNull() {
            ResolvedPath path = ResolvedPath.of("/data/file.csv");

            assertNull(path.resolveRelative(null));
        }

        @Test
        @DisplayName("should handle relative path with relative dir from root")
        void shouldHandleRelativeFromRoot() {
            ResolvedPath path = ResolvedPath.of("/data/export/file.csv");

            String result = path.resolveRelative("other.txt");
            assertEquals("/data/export/other.txt", result);
        }

        @Test
        @DisplayName("should handle relative path when dir is empty")
        void shouldHandleRelativeWhenDirEmpty() {
            ResolvedPath path = ResolvedPath.of("file.csv");

            String result = path.resolveRelative("other.txt");
            assertEquals("/other.txt", result);
        }

        @Test
        @DisplayName("should handle empty string pattern")
        void shouldHandleEmptyPattern() {
            ResolvedPath path = ResolvedPath.of("/data/file.csv");

            String result = path.resolveRelative("");
            assertEquals("/data/", result);
        }

        @Test
        @DisplayName("should handle path that starts with / but has extra spaces")
        void shouldHandlePathStartingWithSlash() {
            ResolvedPath path = ResolvedPath.of("/data/file.csv");

            String result = path.resolveRelative("  /starts with space");
            assertEquals("/data/  /starts with space", result);
        }
    }
}
