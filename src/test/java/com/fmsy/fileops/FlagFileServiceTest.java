package com.fmsy.fileops;

import com.fmsy.exception.FlagCheckException;
import com.fmsy.ftp.FtpClient;
import com.fmsy.util.ResolvedPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FlagFileService Tests")
class FlagFileServiceTest {

    @Mock
    private FtpClient ftpClient;

    @Mock
    private MessageSender messageSender;

    private FlagFileService flagFileService;

    @BeforeEach
    void setUp() {
        flagFileService = new FlagFileService(messageSender);
    }

    @Nested
    @DisplayName("preCheck method")
    class PreCheckTests {

        @Test
        @DisplayName("should return true when opsStr is null")
        void shouldReturnTrueWhenOpsStrIsNull() {
            boolean result = flagFileService.preCheck(ftpClient, null, null);
            assertTrue(result);
        }

        @Test
        @DisplayName("should return true when opsStr is empty")
        void shouldReturnTrueWhenOpsStrIsEmpty() {
            boolean result = flagFileService.preCheck(ftpClient, "", null);
            assertTrue(result);
        }

        @Test
        @DisplayName("should return true when READY file exists")
        void shouldReturnTrueWhenReadyFileExists() {
            when(ftpClient.exists("/data/ready.flg")).thenReturn(true);
            boolean result = flagFileService.preCheck(ftpClient, "READY;/data/ready.flg", null);
            assertTrue(result);
            verify(ftpClient).exists("/data/ready.flg");
        }

        @Test
        @DisplayName("should return false when READY file does not exist")
        void shouldReturnFalseWhenReadyFileNotExists() {
            when(ftpClient.exists("/data/missing.flg")).thenReturn(false);
            boolean result = flagFileService.preCheck(ftpClient, "READY;/data/missing.flg", null);
            assertFalse(result);
        }

        @Test
        @DisplayName("should return true when FLAG file exists (existence check)")
        void shouldReturnTrueWhenFlagFileExists() {
            when(ftpClient.exists("/data/flag.flg")).thenReturn(true);
            boolean result = flagFileService.preCheck(ftpClient, "FLAG;/data/flag.flg", null);
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when FLAG file does not exist")
        void shouldReturnFalseWhenFlagFileNotExists() {
            when(ftpClient.exists("/data/missing.flg")).thenReturn(false);
            boolean result = flagFileService.preCheck(ftpClient, "FLAG;/data/missing.flg", null);
            assertFalse(result);
        }

        @Test
        @DisplayName("should skip empty operations")
        void shouldSkipEmptyOperations() {
            boolean result = flagFileService.preCheck(ftpClient, ",,", null);
            assertTrue(result);
            verify(ftpClient, never()).exists(anyString());
        }

        @Test
        @DisplayName("should log warning for unknown pre-operation keyword")
        void shouldLogWarningForUnknownKeyword() {
            boolean result = flagFileService.preCheck(ftpClient, "UNKNOWN;/path", null);
            assertTrue(result);
        }

        @Test
        @DisplayName("should throw FlagCheckException when FLAG comparison fails")
        void shouldThrowFlagCheckExceptionOnComparisonFailure() throws Exception {
            String flagPath = "/data/flag.flg";
            when(ftpClient.exists(flagPath)).thenReturn(true);
            when(ftpClient.getInputStream(flagPath)).thenReturn(
                    new java.io.ByteArrayInputStream("100".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            when(ftpClient.countFileLines(anyString())).thenReturn(50);

            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");

            assertThrows(FlagCheckException.class,
                    () -> flagFileService.preCheck(ftpClient, "FLAG;" + flagPath + ";L", fileInfo));
        }

        @Test
        @DisplayName("should return false when FLAG check file not found (existence check)")
        void shouldReturnFalseWhenFlagCheckFileNotFound() {
            when(ftpClient.exists("/data/missing.flg")).thenReturn(false);
            boolean result = flagFileService.preCheck(ftpClient, "FLAG;/data/missing.flg;?", null);
            assertFalse(result);
        }

        @Test
        @DisplayName("should throw FlagCheckException when FLAG file exists but is empty (E, not N)")
        void shouldThrowWhenFlagFileIsEmpty() throws Exception {
            String flagPath = "/data/empty.flg";
            when(ftpClient.exists(flagPath)).thenReturn(true);
            when(ftpClient.getInputStream(flagPath)).thenReturn(
                    new java.io.ByteArrayInputStream(new byte[0]));

            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");

            assertThrows(FlagCheckException.class,
                    () -> flagFileService.preCheck(ftpClient, "FLAG;" + flagPath + ";L", fileInfo));
        }
    }

    @Nested
    @DisplayName("checkReady method")
    class CheckReadyTests {

        @Test
        @DisplayName("should return true and not log when file exists")
        void shouldReturnTrueWhenFileExists() {
            when(ftpClient.exists("/path/to/file.txt")).thenReturn(true);
            boolean result = flagFileService.checkReady(ftpClient, "/path/to/file.txt");
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false and log warning when file does not exist")
        void shouldReturnFalseWhenFileNotExists() {
            when(ftpClient.exists("/missing/file.txt")).thenReturn(false);
            boolean result = flagFileService.checkReady(ftpClient, "/missing/file.txt");
            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("resolvePath method")
    class ResolvePathTests {

        @Test
        @DisplayName("should return absolute path unchanged")
        void shouldReturnAbsolutePathUnchanged() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/original.csv");
            String result = flagFileService.resolvePath("/absolute/path.flg", fileInfo);
            assertEquals("/absolute/path.flg", result);
        }

        @Test
        @DisplayName("should apply path inheritance for relative path")
        void shouldApplyPathInheritanceForRelativePath() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            String result = flagFileService.resolvePath("flag.flg", fileInfo);
            assertEquals("/data/export/flag.flg", result);
        }

        @Test
        @DisplayName("should return raw path when fileInfo is null")
        void shouldReturnRawPathWhenFileInfoIsNull() {
            String result = flagFileService.resolvePath("flag.flg", (ResolvedPath) null);
            assertEquals("flag.flg", result);
        }

        @Test
        @DisplayName("should return raw path when fileInfo dir is empty")
        void shouldReturnRawPathWhenDirIsEmpty() {
            ResolvedPath fileInfo = ResolvedPath.of("file.csv"); // no directory
            String result = flagFileService.resolvePath("flag.flg", fileInfo);
            assertEquals("flag.flg", result);
        }
    }

    @Nested
    @DisplayName("normalizePath method")
    class NormalizePathTests {

        @Test
        @DisplayName("should return path unchanged when no parent traversal")
        void shouldReturnPathUnchangedWhenNoParentTraversal() {
            String result = FlagFileService.normalizePath("/data/export/file.flg");
            assertEquals("/data/export/file.flg", result);
        }

        @Test
        @DisplayName("should resolve single parent traversal")
        void shouldResolveSingleParentTraversal() {
            String result = FlagFileService.normalizePath("/data/export/BR001/../all.flg");
            assertEquals("/data/export/all.flg", result);
        }

        @Test
        @DisplayName("should resolve multiple parent traversals")
        void shouldResolveMultipleParentTraversals() {
            String result = FlagFileService.normalizePath("/a/b/c/../d/../e.flg");
            assertEquals("/a/b/e.flg", result);
        }

        @Test
        @DisplayName("should return null when path is null")
        void shouldReturnNullWhenPathIsNull() {
            String result = FlagFileService.normalizePath(null);
            assertNull(result);
        }

        @Test
        @DisplayName("should handle path with only parent traversals")
        void shouldHandlePathWithOnlyParentTraversals() {
            String result = FlagFileService.normalizePath("/a/../b.flg");
            assertEquals("/b.flg", result);
        }
    }

    @Nested
    @DisplayName("filterOpsByType static method")
    class FilterOpsByTypeTests {

        @Test
        @DisplayName("should return null when operationsStr is null")
        void shouldReturnNullWhenOpsStrIsNull() {
            String result = FlagFileService.filterOpsByType(null, "FB");
            assertNull(result);
        }

        @Test
        @DisplayName("should return null when operationsStr is empty")
        void shouldReturnNullWhenOpsStrIsEmpty() {
            String result = FlagFileService.filterOpsByType("", "FB");
            assertNull(result);
        }

        @Test
        @DisplayName("should return original string when opType is null")
        void shouldReturnOriginalWhenOpTypeIsNull() {
            String result = FlagFileService.filterOpsByType("FB;/path1,SUB;/path2", null);
            assertEquals("FB;/path1,SUB;/path2", result);
        }

        @Test
        @DisplayName("should filter by operation type case-insensitively")
        void shouldFilterByOperationTypeCaseInsensitive() {
            String result = FlagFileService.filterOpsByType("FB;/path1,fb;/path2,SUB;/path3", "fb");
            assertEquals("FB;/path1,fb;/path2", result);
        }

        @Test
        @DisplayName("should return null when no matching operations")
        void shouldReturnNullWhenNoMatchingOperations() {
            String result = FlagFileService.filterOpsByType("DEL;/path,TOTAL;/path2", "FB");
            assertNull(result);
        }

        @Test
        @DisplayName("should handle multiple operations of same type")
        void shouldHandleMultipleOperationsOfSameType() {
            String result = FlagFileService.filterOpsByType("FB;/path1,MSG;/x,FB;/path2", "FB");
            assertEquals("FB;/path1,FB;/path2", result);
        }
    }

    @Nested
    @DisplayName("process method")
    class ProcessTests {

        @Test
        @DisplayName("should do nothing when operationsStr is null")
        void shouldDoNothingWhenOpsStrIsNull() throws Exception {
            flagFileService.process(ftpClient, null);
            verify(ftpClient, never()).getOutputStream(anyString());
        }

        @Test
        @DisplayName("should do nothing when operationsStr is empty")
        void shouldDoNothingWhenOpsStrIsEmpty() throws Exception {
            flagFileService.process(ftpClient, "");
            verify(ftpClient, never()).getOutputStream(anyString());
        }

        @Test
        @DisplayName("should send message when MSG operation is processed")
        void shouldSendMessageWhenMsgOperationIsProcessed() throws Exception {
            flagFileService.process(ftpClient, "MSG;target;body");
            verify(messageSender).send("target", "body");
        }

        @Test
        @DisplayName("should log warning for unknown post-operation keyword")
        void shouldLogWarningForUnknownKeyword() {
            flagFileService.process(ftpClient, "UNKNOWN;/path");
            // No exception thrown, warning is logged
        }
    }
}
