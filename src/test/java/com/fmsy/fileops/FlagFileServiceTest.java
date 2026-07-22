package com.fmsy.fileops;

import com.fmsy.exception.FlagCheckException;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.MessageConfig;
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

import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    @Mock
    private MessageConfigService messageConfigService;

    private FlagFileService flagFileService;

    @BeforeEach
    void setUp() {
        flagFileService = new FlagFileService(messageConfigService, messageSender);
    }

    // ==================== 文件名短码展开 ====================

    @Nested
    @DisplayName("expandFileNameShortCodes method")
    class ExpandFileNameShortCodesTests {

        @Test
        @DisplayName("should expand S to {stem}")
        void shouldExpandStem() {
            assertEquals("{stem}", FlagFileService.expandFileNameShortCodes("S"));
        }

        @Test
        @DisplayName("should expand S.ok to {stem}.ok")
        void shouldExpandStemWithExtension() {
            assertEquals("{stem}.ok", FlagFileService.expandFileNameShortCodes("S.ok"));
        }

        @Test
        @DisplayName("should expand D/S.flg to {dir}/{stem}.flg")
        void shouldExpandDirAndStem() {
            assertEquals("{dir}/{stem}.flg", FlagFileService.expandFileNameShortCodes("D/S.flg"));
        }

        @Test
        @DisplayName("should not expand short codes adjacent to letters")
        void shouldNotExpandAdjacentToLetters() {
            // "DATA" — S 两侧都是字母，不展开
            assertEquals("DATA", FlagFileService.expandFileNameShortCodes("DATA"));
        }

        @Test
        @DisplayName("should expand N to {name}")
        void shouldExpandName() {
            assertEquals("{name}", FlagFileService.expandFileNameShortCodes("N"));
        }

        @Test
        @DisplayName("should expand E to {ext}")
        void shouldExpandExt() {
            assertEquals("{ext}", FlagFileService.expandFileNameShortCodes("E"));
        }

        @Test
        @DisplayName("should expand P to {path}")
        void shouldExpandPath() {
            assertEquals("{path}", FlagFileService.expandFileNameShortCodes("P"));
        }

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(FlagFileService.expandFileNameShortCodes(null));
        }

        @Test
        @DisplayName("should return empty for empty input")
        void shouldReturnEmptyForEmpty() {
            assertEquals("", FlagFileService.expandFileNameShortCodes(""));
        }
    }

    // ==================== 前置检查 ====================

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
        @DisplayName("should return true when L file exists (existence check)")
        void shouldReturnTrueWhenFileExists() {
            when(ftpClient.exists("/data/ready.flg")).thenReturn(true);
            boolean result = flagFileService.preCheck(ftpClient, "L:/data/ready.flg", null);
            assertTrue(result);
            verify(ftpClient).exists("/data/ready.flg");
        }

        @Test
        @DisplayName("should return false when L file does not exist")
        void shouldReturnFalseWhenFileNotExists() {
            when(ftpClient.exists("/data/missing.flg")).thenReturn(false);
            boolean result = flagFileService.preCheck(ftpClient, "L:/data/missing.flg", null);
            assertFalse(result);
        }

        @Test
        @DisplayName("should return false when L:path;00 (empty code) file does not exist")
        void shouldReturnFalseWhenEmptyCodeFileNotExists() {
            when(ftpClient.exists("/data/missing.flg")).thenReturn(false);
            boolean result = flagFileService.preCheck(ftpClient, "L:/data/missing.flg;00", null);
            assertFalse(result);
        }

        @Test
        @DisplayName("should throw FlagCheckException when FLAG comparison fails")
        void shouldThrowFlagCheckExceptionOnComparisonFailure() throws Exception {
            String flagPath = "/data/flag.flg";
            when(ftpClient.exists(flagPath)).thenReturn(true);
            when(ftpClient.getInputStream(flagPath)).thenReturn(
                    new java.io.ByteArrayInputStream("100".getBytes(StandardCharsets.UTF_8)));
            when(ftpClient.countFileLines(anyString())).thenReturn(50);

            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");

            // 03 = L S M, 读标志文件内容 "100", 计算数据文件行数=50, 比对: "100" != "50 ..."
            assertThrows(FlagCheckException.class,
                    () -> flagFileService.preCheck(ftpClient, "L:" + flagPath + ";03", fileInfo));
        }

        @Test
        @DisplayName("should return false when L check file not found (existence check)")
        void shouldReturnFalseWhenCheckFileNotFound() {
            when(ftpClient.exists("/data/missing.flg")).thenReturn(false);
            boolean result = flagFileService.preCheck(ftpClient, "L:/data/missing.flg", null);
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
                    () -> flagFileService.preCheck(ftpClient, "L:" + flagPath + ";03", fileInfo));
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
            boolean result = flagFileService.preCheck(ftpClient, "X:/path", null);
            assertTrue(result);
        }

        @Test
        @DisplayName("should expand filename short codes in pre-check path")
        void shouldExpandShortCodesInPreCheck() {
            when(ftpClient.exists("/data/export/file.ok")).thenReturn(true);
            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            // S 展开为 {stem}，再通过路径继承 + 变量展开变为 file.ok
            // 由于 resolvePath 会先 expandFileNameShortCodes 再 expandPathVariables
            // 最终路径: /data/export/file.ok
            boolean result = flagFileService.preCheck(ftpClient, "L:S.ok", fileInfo);
            assertTrue(result);
            verify(ftpClient).exists("/data/export/file.ok");
        }
    }

    // ==================== checkExists 方法 ====================

    @Nested
    @DisplayName("checkExists method")
    class CheckExistsTests {

        @Test
        @DisplayName("should return true when file exists")
        void shouldReturnTrueWhenFileExists() {
            when(ftpClient.exists("/path/to/file.txt")).thenReturn(true);
            boolean result = flagFileService.checkExists(ftpClient, "/path/to/file.txt");
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when file does not exist")
        void shouldReturnFalseWhenFileNotExists() {
            when(ftpClient.exists("/missing/file.txt")).thenReturn(false);
            boolean result = flagFileService.checkExists(ftpClient, "/missing/file.txt");
            assertFalse(result);
        }
    }

    // ==================== resolvePath 方法 ====================

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
            ResolvedPath fileInfo = ResolvedPath.of("file.csv");
            String result = flagFileService.resolvePath("flag.flg", fileInfo);
            assertEquals("flag.flg", result);
        }

        @Test
        @DisplayName("should expand short codes and resolve path")
        void shouldExpandShortCodesAndResolvePath() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            // S.ok → {stem}.ok → file.ok → /data/export/file.ok
            String result = flagFileService.resolvePath("S.ok", fileInfo);
            assertEquals("/data/export/file.ok", result);
        }
    }

    // ==================== normalizePath 方法 ====================

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

    // ==================== filterOpsByType 静态方法 ====================

    @Nested
    @DisplayName("filterOpsByType static method")
    class FilterOpsByTypeTests {

        @Test
        @DisplayName("should return null when operationsStr is null")
        void shouldReturnNullWhenOpsStrIsNull() {
            String result = FlagFileService.filterOpsByType(null, "F");
            assertNull(result);
        }

        @Test
        @DisplayName("should return null when operationsStr is empty")
        void shouldReturnNullWhenOpsStrIsEmpty() {
            String result = FlagFileService.filterOpsByType("", "F");
            assertNull(result);
        }

        @Test
        @DisplayName("should return original string when opType is null")
        void shouldReturnOriginalWhenOpTypeIsNull() {
            String result = FlagFileService.filterOpsByType("F:/path1,U:/path2", null);
            assertEquals("F:/path1,U:/path2", result);
        }

        @Test
        @DisplayName("should filter by operation type case-insensitively")
        void shouldFilterByOperationTypeCaseInsensitive() {
            String result = FlagFileService.filterOpsByType("F:/path1,f:/path2,U:/path3", "f");
            assertEquals("F:/path1,f:/path2", result);
        }

        @Test
        @DisplayName("should return null when no matching operations")
        void shouldReturnNullWhenNoMatchingOperations() {
            String result = FlagFileService.filterOpsByType("D:/path,T:/path2", "F");
            assertNull(result);
        }

        @Test
        @DisplayName("should handle multiple operations of same type")
        void shouldHandleMultipleOperationsOfSameType() {
            String result = FlagFileService.filterOpsByType("F:/path1,M,U:/path2", "F");
            assertEquals("F:/path1", result);
        }
    }

    // ==================== extractFlagPathPattern 静态方法 ====================

    @Nested
    @DisplayName("extractFlagPathPattern static method")
    class ExtractFlagPathPatternTests {

        @Test
        @DisplayName("should extract path from L operation")
        void shouldExtractPathFromFlagOp() {
            String result = FlagFileService.extractFlagPathPattern("L:S.ok;03");
            assertEquals("S.ok", result);
        }

        @Test
        @DisplayName("should extract path from L operation without content code")
        void shouldExtractPathFromFlagOpWithoutCode() {
            String result = FlagFileService.extractFlagPathPattern("L:*.flg");
            assertEquals("*.flg", result);
        }

        @Test
        @DisplayName("should return null when no L operation")
        void shouldReturnNullWhenNoFlagOp() {
            String result = FlagFileService.extractFlagPathPattern("F:/path1;01");
            assertNull(result);
        }

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(FlagFileService.extractFlagPathPattern(null));
        }

        @Test
        @DisplayName("should return null for empty input")
        void shouldReturnNullForEmpty() {
            assertNull(FlagFileService.extractFlagPathPattern(""));
        }
    }

    // ==================== 后置处理 ====================

    @Nested
    @DisplayName("process method")
    class ProcessTests {

        @Test
        @DisplayName("should do nothing when operationsStr is null")
        void shouldDoNothingWhenOpsStrIsNull() throws Exception {
            flagFileService.process(ftpClient, (String) null);
            verify(ftpClient, never()).getOutputStream(anyString());
        }

        @Test
        @DisplayName("should do nothing when operationsStr is empty")
        void shouldDoNothingWhenOpsStrIsEmpty() throws Exception {
            flagFileService.process(ftpClient, "");
            verify(ftpClient, never()).getOutputStream(anyString());
        }

        @Test
        @DisplayName("should generate feedback file with content code")
        void shouldGenerateFeedbackFile() throws Exception {
            when(ftpClient.getOutputStream("/data/export/file.ret")).thenReturn(
                    new java.io.ByteArrayOutputStream());

            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            // F:path;01 — 01 = SUCCESS
            flagFileService.process(ftpClient, "F:/data/export/file.ret;01", fileInfo, null);

            verify(ftpClient).getOutputStream("/data/export/file.ret");
            verify(ftpClient).completePendingCommand();
        }

        @Test
        @DisplayName("should generate sub-flag file with content code")
        void shouldGenerateSubFlagFile() throws Exception {
            when(ftpClient.getOutputStream("/data/export/file.ready")).thenReturn(
                    new java.io.ByteArrayOutputStream());

            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            // U:path;02 — 02 = OK
            flagFileService.process(ftpClient, "U:/data/export/file.ready;02", fileInfo, null);

            verify(ftpClient).getOutputStream("/data/export/file.ready");
            verify(ftpClient).completePendingCommand();
        }

        @Test
        @DisplayName("should generate total-flag file with content code")
        void shouldGenerateTotalFlagFile() throws Exception {
            when(ftpClient.getOutputStream("/data/export/all.flg")).thenReturn(
                    new java.io.ByteArrayOutputStream());

            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            // T:path;02 — 02 = OK
            flagFileService.process(ftpClient, "T:/data/export/all.flg;02", fileInfo, null);

            verify(ftpClient).getOutputStream("/data/export/all.flg");
            verify(ftpClient).completePendingCommand();
        }

        @Test
        @DisplayName("should generate file with empty content when code is 00")
        void shouldGenerateEmptyContentFile() throws Exception {
            ByteArrayOutputStreamHolder bos = new ByteArrayOutputStreamHolder();
            when(ftpClient.getOutputStream("/data/export/empty.flg")).thenReturn(bos);

            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            // F:path;00 — 00 = 空内容
            flagFileService.process(ftpClient, "F:/data/export/empty.flg;00", fileInfo, null);

            verify(ftpClient).getOutputStream("/data/export/empty.flg");
            assertEquals(0, bos.getSize(), "Empty content file should be 0 bytes");
        }

        @Test
        @DisplayName("should generate file with empty content when code is omitted")
        void shouldGenerateEmptyContentWhenCodeOmitted() throws Exception {
            ByteArrayOutputStreamHolder bos = new ByteArrayOutputStreamHolder();
            when(ftpClient.getOutputStream("/data/export/empty.flg")).thenReturn(bos);

            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            // F:path — 无内容编号 = 空内容
            flagFileService.process(ftpClient, "F:/data/export/empty.flg", fileInfo, null);

            verify(ftpClient).getOutputStream("/data/export/empty.flg");
            assertEquals(0, bos.getSize(), "Empty content file should be 0 bytes");
        }

        @Test
        @DisplayName("should send message when M operation is processed")
        void shouldSendMessageWhenMsgOperationProcessed() throws Exception {
            MessageConfig msgConfig = new MessageConfig();
            msgConfig.setChannelType("LOG");
            msgConfig.setTarget("fmsy.test");
            msgConfig.setMessageTemplate("Transfer completed");
            when(messageConfigService.getConfig("TEST", "001")).thenReturn(msgConfig);

            flagFileService.process(ftpClient, "M", null, null, "TEST", "001");
            verify(messageSender).send("LOG:fmsy.test", "Transfer completed");
        }

        @Test
        @DisplayName("should log warning when message config not found")
        void shouldLogWarningWhenMsgConfigNotFound() throws Exception {
            when(messageConfigService.getConfig("TEST", "999")).thenReturn(null);

            // Should not throw
            flagFileService.process(ftpClient, "M", null, null, "TEST", "999");
            verify(messageSender, never()).send(anyString(), anyString());
        }

        @Test
        @DisplayName("should log warning for unknown post-operation keyword")
        void shouldLogWarningForUnknownKeyword() {
            flagFileService.process(ftpClient, "X:/path");
        }

        @Test
        @DisplayName("should expand filename short codes in post-process paths")
        void shouldExpandShortCodesInPostProcess() throws Exception {
            ByteArrayOutputStreamHolder bos = new ByteArrayOutputStreamHolder();
            // S.ok + 路径继承 → /data/export/file.ok
            when(ftpClient.getOutputStream("/data/export/file.ok")).thenReturn(bos);

            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            // F:S.ok;01 — S 展开为 {stem} → file.ok
            flagFileService.process(ftpClient, "F:S.ok;01", fileInfo, null);

            verify(ftpClient).getOutputStream("/data/export/file.ok");
            verify(ftpClient).completePendingCommand();
        }

        @Test
        @DisplayName("should expand short codes in delete pattern")
        void shouldExpandShortCodesInDelete() throws Exception {
            when(ftpClient.listFiles("/data/export/file.ok")).thenReturn(new String[]{"/data/export/file.ok"});
            when(ftpClient.deleteFile("/data/export/file.ok")).thenReturn(true);

            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            // D:S.ok — S 展开为 {stem} → file.ok
            flagFileService.process(ftpClient, "D:S.ok", fileInfo, null);

            verify(ftpClient).deleteFile("/data/export/file.ok");
        }

        @Test
        @DisplayName("should expand short codes in rename pattern")
        void shouldExpandShortCodesInRename() throws Exception {
            when(ftpClient.listFiles("/data/export/file.flg")).thenReturn(new String[]{"/data/export/file.flg"});
            when(ftpClient.rename("/data/export/file.flg", "/data/export/file.done")).thenReturn(true);

            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            // R:D/S.flg;D/S.done — 展开 S 和 D
            // 注意: REN 操作中 from 和 to 都是文件模式，不是路径继承
            flagFileService.process(ftpClient, "R:/data/export/S.flg;/data/export/S.done", fileInfo, null);

            verify(ftpClient).rename("/data/export/file.flg", "/data/export/file.done");
        }
    }

    // ==================== ContentCode 枚举 ====================

    @Nested
    @DisplayName("ContentCode enum")
    class ContentCodeTests {

        @Test
        @DisplayName("should find code by number")
        void shouldFindCodeByNumber() {
            assertEquals(ContentCode.SUCCESS, ContentCode.fromCode("01"));
            assertEquals(ContentCode.OK, ContentCode.fromCode("02"));
            assertEquals(ContentCode.L_S_M, ContentCode.fromCode("03"));
            assertEquals(ContentCode.EMPTY, ContentCode.fromCode("00"));
        }

        @Test
        @DisplayName("should return null for unknown code")
        void shouldReturnNullForUnknownCode() {
            assertNull(ContentCode.fromCode("99"));
        }

        @Test
        @DisplayName("should detect empty code")
        void shouldDetectEmptyCode() {
            assertTrue(ContentCode.isEmpty("00"));
            assertTrue(ContentCode.isEmpty(null));
            assertTrue(ContentCode.isEmpty(""));
            assertFalse(ContentCode.isEmpty("01"));
            assertFalse(ContentCode.isEmpty("03"));
        }

        @Test
        @DisplayName("should have correct templates")
        void shouldHaveCorrectTemplates() {
            assertEquals("", ContentCode.EMPTY.getTemplate());
            assertEquals("SUCCESS", ContentCode.SUCCESS.getTemplate());
            assertEquals("OK", ContentCode.OK.getTemplate());
            assertEquals("L S M", ContentCode.L_S_M.getTemplate());
            assertEquals("C", ContentCode.COUNT.getTemplate());
        }
    }

    // ==================== 辅助类 ====================

    /**
     * 辅助类：记录 ByteArrayOutputStream 的字节数，用于验证空内容。
     */
    static class ByteArrayOutputStreamHolder extends java.io.ByteArrayOutputStream {
        int getSize() {
            return count;
        }
    }
}