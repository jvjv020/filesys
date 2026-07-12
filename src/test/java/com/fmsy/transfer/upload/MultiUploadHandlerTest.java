package com.fmsy.transfer.upload;

import com.fmsy.enums.CommandType;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
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

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MultiUploadHandler Tests")
class MultiUploadHandlerTest {

    @Mock
    private DetailRepository detailRepository;

    @Mock
    private UploadSupport uploadSupport;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private FtpClient ftpClient;

    private IntFunction<ExecutorService> batchExecutorFactory;
    private MultiUploadHandler handler;

    private Command command;
    private TransferConfig config;
    private Result result;

    @BeforeEach
    void setUp() {
        batchExecutorFactory = size -> Executors.newFixedThreadPool(size);
        handler = new MultiUploadHandler(detailRepository, uploadSupport, transferSupport,
                batchExecutorFactory);

        command = new Command();
        command.setId(1L);
        command.setCategoryCode("CAT001");
        command.setControlCode("CTRL001");

        config = new TransferConfig();
        config.setFtpName("ftp1");
        config.setFilePath("/data/files/");
        config.setParserType("CSV");
        config.setTableName("mytable");
        config.setDbName("DB1");

        result = new Result();
    }

    @Nested
    @DisplayName("handle - BATCH mode")
    class HandleBatchMode {

        @BeforeEach
        void setupBatchModeChain() {
            command.setCommandType(CommandType.BATCH);
        }

        @Test
        @DisplayName("should handle batch with details using processSingleFile")
        void shouldHandleBatchWithDetails() throws Exception {
            Map<String, Object> detail1 = new HashMap<>();
            detail1.put(ColumnNames.DETAIL_ID, 100L);
            detail1.put(ColumnNames.FILE_NAME, "file1.csv");
            detail1.put(ColumnNames.FIELD_NAME, "REGION");
            detail1.put(ColumnNames.FIELD_VALUE, "EAST");

            Map<String, Object> detail2 = new HashMap<>();
            detail2.put(ColumnNames.DETAIL_ID, 101L);
            detail2.put(ColumnNames.FILE_NAME, "file2.csv");
            detail2.put(ColumnNames.FIELD_NAME, "REGION");
            detail2.put(ColumnNames.FIELD_VALUE, "WEST");

            when(detailRepository.findUploadDetails(1L, ColumnNames.STATUS_EMPTY))
                    .thenReturn(Arrays.asList(detail1, detail2));

            ResolvedPath fileInfo = ResolvedPath.of("/data/files/file1.csv");
            when(transferSupport.resolveFilePath(anyString(), any(Map.class))).thenReturn(fileInfo);

            // processSingleFile returns success for both files
            when(uploadSupport.processSingleFile(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(new UploadSupport.UploadResult(100, 1, 0, 0, null));

            handler.handle(command, config, result);

            assertNotNull(result.getResult());
            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            verify(uploadSupport, times(2)).processSingleFile(anyString(), any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("should mark detail error when processSingleFile returns error")
        void shouldMarkDetailErrorOnProcessSingleFileError() throws Exception {
            Map<String, Object> detail = new HashMap<>();
            detail.put(ColumnNames.DETAIL_ID, 400L);
            detail.put(ColumnNames.FILE_NAME, "file3.csv");
            detail.put(ColumnNames.FIELD_NAME, "REGION");
            detail.put(ColumnNames.FIELD_VALUE, "EAST");

            when(detailRepository.findUploadDetails(1L, ColumnNames.STATUS_EMPTY))
                    .thenReturn(Collections.singletonList(detail));

            ResolvedPath fileInfo = ResolvedPath.of("/data/files/file3.csv");
            when(transferSupport.resolveFilePath(anyString(), any(Map.class))).thenReturn(fileInfo);

            when(uploadSupport.processSingleFile(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(new UploadSupport.UploadResult(0, 0, 0, 1, ColumnNames.STATUS_ERROR));

            handler.handle(command, config, result);

            verify(detailRepository).updateStatus(400L, ColumnNames.STATUS_ERROR, null);
            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }

        @Test
        @DisplayName("should skip empty detail file name")
        void shouldSkipEmptyFileName() throws Exception {
            Map<String, Object> detail = new HashMap<>();
            detail.put(ColumnNames.DETAIL_ID, 200L);
            detail.put(ColumnNames.FILE_NAME, "");
            detail.put(ColumnNames.FIELD_NAME, "REGION");
            detail.put(ColumnNames.FIELD_VALUE, "EAST");

            when(detailRepository.findUploadDetails(1L, ColumnNames.STATUS_EMPTY))
                    .thenReturn(Collections.singletonList(detail));

            handler.handle(command, config, result);

            verify(detailRepository).updateStatus(200L, ColumnNames.STATUS_SKIPPED, null);
        }
    }

    @Nested
    @DisplayName("handle - SERIAL mode")
    class HandleSerialMode {

        @BeforeEach
        void setupSerialMode() throws Exception {
            command.setCommandType(CommandType.SERIAL);

            ResolvedPath dirInfo = ResolvedPath.of("/data/files/");
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(dirInfo);
            // 所有 executeWithClient 调用都传递 ftpClient mock
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
        }

        @Test
        @DisplayName("should handle serial mode with directory listing and pre-scan")
        void shouldHandleSerialMode() throws Exception {
            // No FLAG preOps → prescan passes all files
            when(ftpClient.listFiles(anyString())).thenReturn(new String[]{"/data/files/file1.csv"});
            when(uploadSupport.processSingleFile(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(new UploadSupport.UploadResult(100, 1, 0, 0, null));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            assertEquals(100, result.getRecordCount());
        }

        @Test
        @DisplayName("should prescan and filter flag files")
        void shouldPrescanAndFilterFlagFiles() throws Exception {
            config.setPreOperations("FLAG:{stem}.OK");
            // Mock listFiles to return data files AND flag files
            String[] allFiles = {"/data/files/data1.csv", "/data/files/data1.OK",
                    "/data/files/data2.csv", "/data/files/data2.OK"};
            when(ftpClient.listFiles(anyString())).thenReturn(allFiles);

            // processSingleFile returns success only for data files
            when(uploadSupport.processSingleFile(eq("ftp1"), any(), any(), contains(".csv"), any()))
                    .thenReturn(new UploadSupport.UploadResult(100, 1, 0, 0, null));

            handler.handle(command, config, result);

            // Only 2 data files processed, flag files filtered
            verify(uploadSupport, times(2)).processSingleFile(anyString(), any(), any(), anyString(), any());
            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("should count processSingleFile error as FAIL in serial mode")
        void shouldCountErrorAsFail() throws Exception {
            when(ftpClient.listFiles(anyString())).thenReturn(new String[]{"/data/files/file1.csv"});
            when(uploadSupport.processSingleFile(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(new UploadSupport.UploadResult(0, 0, 0, 1, ColumnNames.STATUS_ERROR));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }

        @Test
        @DisplayName("should return skipped when no files listed")
        void shouldReturnSkippedWhenNoFiles() throws Exception {
            when(ftpClient.listFiles(anyString())).thenReturn(new String[0]);

            handler.handle(command, config, result);
            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("should return skipped when preCheck returns false (flag not found)")
        void shouldReturnSkippedWhenFlagNotFound() throws Exception {
            when(ftpClient.listFiles(anyString())).thenReturn(new String[]{"/data/files/file1.csv"});
            when(uploadSupport.processSingleFile(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(new UploadSupport.UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED));

            handler.handle(command, config, result);

            assertNotNull(result.getResult());
            assertNotEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        }

        @Test
        @DisplayName("should handle all files filtered out by pre-scan")
        void shouldHandleAllFilesFilteredByPrescan() throws Exception {
            config.setPreOperations("FLAG:{stem}.OK");
            // Only flag files returned (no corresponding data files)
            String[] allFiles = {"/data/files/data1.OK", "/data/files/data2.OK"};
            when(ftpClient.listFiles(anyString())).thenReturn(allFiles);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
            verify(uploadSupport, never()).processSingleFile(anyString(), any(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("prescanDataFiles")
    class PrescanDataFilesTests {

        @Test
        @DisplayName("should return all files when flagPattern is null")
        void shouldReturnAllWhenNoFlagPattern() {
            String[] files = {"file1.csv", "file2.csv"};
            List<String> result = handler.prescanDataFiles(files, null);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("should filter out known flag files and include data files with flags")
        void shouldFilterFlagFiles() {
            String[] files = {"data1.csv", "data1.OK", "data2.csv", "data2.OK"};
            List<String> result = handler.prescanDataFiles(files, "{stem}.OK");
            assertEquals(2, result.size());
            assertTrue(result.contains("data1.csv"));
            assertTrue(result.contains("data2.csv"));
        }

        @Test
        @DisplayName("should warn and exclude data files without flags")
        void shouldExcludeDataFilesWithoutFlags() {
            String[] files = {"data1.csv", "data1.OK", "orphan.csv"};
            List<String> result = handler.prescanDataFiles(files, "{stem}.OK");
            assertEquals(1, result.size());
            assertTrue(result.contains("data1.csv"));
        }

        @Test
        @DisplayName("should handle empty file list")
        void shouldHandleEmptyList() {
            List<String> result = handler.prescanDataFiles(new String[0], "{stem}.OK");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should handle null file list")
        void shouldHandleNullList() {
            List<String> result = handler.prescanDataFiles(null, "{stem}.OK");
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("extractFlagPathPattern")
    class ExtractFlagPathPatternTests {

        @Test
        @DisplayName("should extract simple FLAG pattern")
        void shouldExtractSimpleFlag() {
            assertEquals("{stem}.OK", MultiUploadHandler.extractFlagPathPattern("FLAG:{stem}.OK"));
        }

        @Test
        @DisplayName("should extract FLAG pattern with mode")
        void shouldExtractFlagWithMode() {
            assertEquals("{stem}.OK", MultiUploadHandler.extractFlagPathPattern("FLAG:{stem}.OK;L"));
        }

        @Test
        @DisplayName("should extract first FLAG from multiple ops")
        void shouldExtractFirstFlagFromMultiple() {
            String ops = "FLAG:{stem}.OK,READY:other.txt";
            assertEquals("{stem}.OK", MultiUploadHandler.extractFlagPathPattern(ops));
        }

        @Test
        @DisplayName("should return null when no FLAG or READY")
        void shouldReturnNullWhenNoFlag() {
            assertNotNull(MultiUploadHandler.extractFlagPathPattern("READY:check.txt"));
            assertEquals("check.txt", MultiUploadHandler.extractFlagPathPattern("READY:check.txt"));
            assertNull(MultiUploadHandler.extractFlagPathPattern(""));
            assertNull(MultiUploadHandler.extractFlagPathPattern(null));
        }
    }

    @Nested
    @DisplayName("resolveFlagName")
    class ResolveFlagNameTests {

        @Test
        @DisplayName("should resolve {stem} pattern")
        void shouldResolveStemPattern() {
            ResolvedPath info = ResolvedPath.of("/data/file.csv");
            String result = MultiUploadHandler.resolveFlagName("{stem}.OK", info);
            assertEquals("/data/file.OK", result);
        }

        @Test
        @DisplayName("should resolve {name} pattern")
        void shouldResolveNamePattern() {
            ResolvedPath info = ResolvedPath.of("/data/file.csv");
            String result = MultiUploadHandler.resolveFlagName("{name}.flag", info);
            assertEquals("/data/file.csv.flag", result);
        }

        @Test
        @DisplayName("should handle relative path without dir")
        void shouldHandleRelativePath() {
            ResolvedPath info = ResolvedPath.of("file.csv");
            String result = MultiUploadHandler.resolveFlagName("{stem}.OK", info);
            assertEquals("file.OK", result);
        }

        @Test
        @DisplayName("should return null for null inputs")
        void shouldReturnNullForNull() {
            assertNull(MultiUploadHandler.resolveFlagName(null, ResolvedPath.of("f.csv")));
            assertNull(MultiUploadHandler.resolveFlagName("{stem}.OK", null));
        }
    }
}
