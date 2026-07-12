package com.fmsy.transfer;

import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.placeholder.PlaceholderResolver;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferSupport Tests")
class TransferSupportTest {

    @Mock
    private PlaceholderResolver placeholderResolver;

    @Mock
    private FlagFileService flagFileService;

    @Mock
    private FtpPool ftpPool;

    @Mock
    private FtpClient ftpClient;

    private TransferSupport transferSupport;

    @BeforeEach
    void setUp() {
        transferSupport = new TransferSupport(placeholderResolver, flagFileService, ftpPool);
    }

    @Nested
    @DisplayName("splitFieldValues")
    class SplitFieldValuesTests {

        @Test
        @DisplayName("should split field names and values by comma")
        void shouldSplitFieldNamesAndValues() {
            Map<String, String> result = TransferSupport.splitFieldValues("REGION,STATUS", "EAST,ACTIVE");

            assertEquals(2, result.size());
            assertEquals("EAST", result.get("REGION"));
            assertEquals("ACTIVE", result.get("STATUS"));
        }

        @Test
        @DisplayName("should handle mismatched name value counts")
        void shouldHandleMismatchedCounts() {
            Map<String, String> result = TransferSupport.splitFieldValues("A,B,C", "1,2");

            assertEquals(2, result.size());
            assertEquals("1", result.get("A"));
            assertEquals("2", result.get("B"));
        }

        @Test
        @DisplayName("should return empty map for null inputs")
        void shouldReturnEmptyMapForNullInputs() {
            Map<String, String> result1 = TransferSupport.splitFieldValues(null, null);
            Map<String, String> result2 = TransferSupport.splitFieldValues("", "");
            Map<String, String> result3 = TransferSupport.splitFieldValues("A", null);

            assertTrue(result1.isEmpty());
            assertTrue(result2.isEmpty());
            assertTrue(result3.isEmpty());
        }

        @Test
        @DisplayName("should trim whitespace from names and values")
        void shouldTrimWhitespace() {
            Map<String, String> result = TransferSupport.splitFieldValues(" A , B ", " 1 , 2 ");

            assertEquals("1", result.get("A"));
            assertEquals("2", result.get("B"));
        }

        @Test
        @DisplayName("should ignore empty field names")
        void shouldIgnoreEmptyFieldNames() {
            Map<String, String> result = TransferSupport.splitFieldValues(",A,", ",value,");

            assertEquals(1, result.size());
            assertFalse(result.containsKey(""));
            assertEquals("value", result.get("A"));
        }
    }

    @Nested
    @DisplayName("buildContext")
    class BuildContextTests {

        @Test
        @DisplayName("should build context with extra info from command")
        void shouldBuildContextWithExtraInfo() {
            Command command = new Command();
            command.setExtraInfo("extra_value");

            Map<String, String> context = transferSupport.buildContext(command, null, null);

            assertEquals("extra_value", context.get("EXTRA_INFO"));
        }

        @Test
        @DisplayName("should build context with split fields and field value")
        void shouldBuildContextWithSplitFields() {
            Command command = new Command();
            command.setExtraInfo("main_info");

            Map<String, String> context = transferSupport.buildContext(command, "REGION,STATUS", "EAST,ACTIVE");

            assertEquals("main_info", context.get("EXTRA_INFO"));
            assertEquals("EAST", context.get("REGION"));
            assertEquals("ACTIVE", context.get("STATUS"));
        }

        @Test
        @DisplayName("should return context with split fields when command is null")
        void shouldReturnContextWithSplitFieldsWhenCommandIsNull() {
            Map<String, String> context = transferSupport.buildContext(null, "FIELD", "value");

            assertEquals(1, context.size());
            assertEquals("value", context.get("FIELD"));
        }

        @Test
        @DisplayName("should not add split fields when parameters are null")
        void shouldNotAddSplitFieldsWhenParametersAreNull() {
            Command command = new Command();
            command.setExtraInfo("info");

            Map<String, String> context = transferSupport.buildContext(command, null, "value");
            assertFalse(context.containsKey("value"));

            context = transferSupport.buildContext(command, "FIELD", null);
            assertFalse(context.containsKey("FIELD"));
        }
    }

    @Nested
    @DisplayName("resolveFilePath")
    class ResolveFilePathTests {

        @Test
        @DisplayName("should resolve file path using placeholder resolver")
        void shouldResolveFilePath() {
            when(placeholderResolver.resolve(eq("/data/{YYYYMMDD}.csv"), any()))
                    .thenReturn("/data/20260615.csv");

            ResolvedPath result = transferSupport.resolveFilePath("/data/{YYYYMMDD}.csv", Map.of());

            assertNotNull(result);
            assertEquals("/data/20260615.csv", result.fullPath());
        }

        @Test
        @DisplayName("should return null for null template")
        void shouldReturnNullForNullTemplate() {
            ResolvedPath result = transferSupport.resolveFilePath(null, Map.of());

            assertNull(result);
        }

        @Test
        @DisplayName("should resolve template with command context")
        void shouldResolveTemplateWithCommandContext() {
            Command command = new Command();
            command.setExtraInfo("cmd_extra");
            when(placeholderResolver.resolve(eq("/{EXTRA_INFO}/file.csv"), any()))
                    .thenReturn("/cmd_extra/file.csv");

            ResolvedPath result = transferSupport.resolveFilePath("/{EXTRA_INFO}/file.csv", command);

            assertNotNull(result);
            assertEquals("/cmd_extra/file.csv", result.fullPath());
        }
    }

    @Nested
    @DisplayName("handleEmptyData")
    class HandleEmptyDataTests {

        @Test
        @DisplayName("should return false for ERROR handling with zero records")
        void shouldReturnFalseForErrorHandling() {
            boolean result = transferSupport.handleEmptyData(0, EmptyDataHandling.ERROR);

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for SKIP handling with zero records")
        void shouldReturnFalseForSkipHandling() {
            boolean result = transferSupport.handleEmptyData(0, EmptyDataHandling.SKIP);

            assertFalse(result);
        }

        @Test
        @DisplayName("should return true for ALLOW handling with zero records")
        void shouldReturnTrueForAllowHandling() {
            boolean result = transferSupport.handleEmptyData(0, EmptyDataHandling.ALLOW);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return true for SEND_EMPTY handling with zero records")
        void shouldReturnTrueForSendEmptyHandling() {
            boolean result = transferSupport.handleEmptyData(0, EmptyDataHandling.SEND_EMPTY);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return true when record count is greater than zero")
        void shouldReturnTrueWhenRecordCountIsGreaterThanZero() {
            boolean result = transferSupport.handleEmptyData(100, EmptyDataHandling.ERROR);

            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("determineMainStatus")
    class DetermineMainStatusTests {

        @Test
        @DisplayName("should return SUCCESS when all succeed")
        void shouldReturnSuccessWhenAllSucceed() {
            String result = TransferSupport.determineMainStatus(true, 0, 0);

            assertEquals(ColumnNames.STATUS_SUCCESS, result);
        }

        @Test
        @DisplayName("should return ERROR when any failed")
        void shouldReturnErrorWhenAnyFailed() {
            String result = TransferSupport.determineMainStatus(false, 1, 0);

            assertEquals(ColumnNames.STATUS_ERROR, result);
        }

        @Test
        @DisplayName("should return SKIPPED when none failed but some skipped")
        void shouldReturnSkippedWhenSomeSkipped() {
            String result = TransferSupport.determineMainStatus(false, 0, 5);

            assertEquals(ColumnNames.STATUS_SKIPPED, result);
        }

        @Test
        @DisplayName("should return ERROR when all failed with no failures counted")
        void shouldReturnErrorWhenAllSucceededButNoFailuresCounted() {
            String result = TransferSupport.determineMainStatus(false, 0, 0);

            assertEquals(ColumnNames.STATUS_ERROR, result);
        }
    }

    @Nested
    @DisplayName("preCheck")
    class PreCheckTests {

        @Test
        @DisplayName("should delegate pre check to flag file service")
        void shouldDelegatePreCheckToFlagFileService() {
            TransferConfig config = new TransferConfig();
            config.setPreOperations("READY:/data/file.csv");
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            when(flagFileService.preCheck(ftpClient, "READY:/data/file.csv", fileInfo))
                    .thenReturn(true);

            boolean result = transferSupport.preCheck(ftpClient, config, fileInfo);

            assertTrue(result);
            verify(flagFileService).preCheck(ftpClient, "READY:/data/file.csv", fileInfo);
        }
    }

    @Nested
    @DisplayName("postProcess")
    class PostProcessTests {

        @Test
        @DisplayName("should delegate post process to flag file service")
        void shouldDelegatePostProcessToFlagFileService() {
            TransferConfig config = new TransferConfig();
            config.setPostOperations("FB:/feedback.txt;L");
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            Map<String, String> extraValues = Map.of("L", "100");

            transferSupport.postProcess(ftpClient, config, fileInfo, extraValues);

            verify(flagFileService).process(ftpClient, "FB:/feedback.txt;L", fileInfo, extraValues);
        }

        @Test
        @DisplayName("should not call flag file service when post operations is null")
        void shouldNotCallFlagFileServiceWhenPostOpsIsNull() {
            TransferConfig config = new TransferConfig();
            config.setPostOperations(null);
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");

            transferSupport.postProcess(ftpClient, config, fileInfo, Map.of());

            verifyNoInteractions(flagFileService);
        }
    }

    @Nested
    @DisplayName("executeWithClient")
    class ExecuteWithClientTests {

        @Test
        @DisplayName("should get client from pool and execute callback")
        void shouldGetClientAndExecuteCallback() throws Exception {
            when(ftpPool.getClient("ftp1")).thenReturn(ftpClient);

            String result = transferSupport.executeWithClient("ftp1", client -> "success");

            assertEquals("success", result);
            verify(ftpPool).getClient("ftp1");
            verify(ftpClient).close();
        }
    }
}
