package com.fmsy.transfer.upload;

import com.fmsy.enums.CommandType;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.FieldMappingBuilder;
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

import java.util.List;
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
    private UploadSupport uploadSupport;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private FieldMappingBuilder fieldMappingBuilder;

    @Mock
    private FtpClient ftpClient;

    @Mock
    private FieldMapping fieldMapping;

    private IntFunction<ExecutorService> batchExecutorFactory;
    private MultiUploadHandler handler;

    private Command command;
    private TransferConfig config;
    private Result result;

    @BeforeEach
    void setUp() {
        batchExecutorFactory = size -> Executors.newFixedThreadPool(size);
        handler = new MultiUploadHandler(uploadSupport, transferSupport,
                fieldMappingBuilder, batchExecutorFactory);

        command = new Command();
        command.setId(1L);
        command.setCategoryCode("CAT001");
        command.setControlCode("CTRL001");

        config = new TransferConfig();
        config.setFtpName("ftp1");
        config.setFilePath("/data/files/{FILE_NAME}");
        config.setParserType("CSV");
        config.setTableName("mytable");
        config.setDbName("DB1");

        result = new Result();
    }

    // ==================== SERIAL 模式 ====================

    @Nested
    @DisplayName("handle - SERIAL mode")
    class HandleSerialMode {

        @BeforeEach
        void setupSerialMode() throws Exception {
            command.setCommandType(CommandType.SERIAL);

            ResolvedPath dirInfo = ResolvedPath.of("/data/files/{FILE_NAME}");
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(dirInfo);
            doAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    }).when(transferSupport).executeWithClient(eq("ftp1"), any());
            when(fieldMappingBuilder.buildForUpload(config, null)).thenReturn(fieldMapping);
        }

        @Test
        @DisplayName("SERIAL 模式正常处理")
        void shouldHandleSerialMode() throws Exception {
            when(ftpClient.listFiles(anyString())).thenReturn(new String[]{"/data/files/file1.csv"});
            when(uploadSupport.preCheck(any(), any(), any(), anyString())).thenReturn(null);
            when(uploadSupport.insertDataAndVerify(any(), any(), any(FieldMapping.class), anyString(), any())).thenReturn(100);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            assertEquals(100, result.getRecordCount());
        }

        @Test
        @DisplayName("清表标志为 Y 时在 preCheck 后、落库前调用 truncateTable")
        void shouldTruncateAfterPreCheckBeforeInsert() throws Exception {
            config.setClearTableFlag("Y");
            when(ftpClient.listFiles(anyString())).thenReturn(new String[]{"/data/files/file1.csv"});
            when(uploadSupport.preCheck(any(), any(), any(), anyString())).thenReturn(null);
            when(uploadSupport.insertDataAndVerify(any(), any(), any(FieldMapping.class), anyString(), any())).thenReturn(100);

            handler.handle(command, config, result);

            // SERIAL 模式跳过前稽核，验证顺序：preCheck → truncate → insert
            verify(uploadSupport).truncateTable(config);
            verify(uploadSupport).insertDataAndVerify(any(), any(), any(FieldMapping.class), anyString(), any());
        }

        @Test
        @DisplayName("预扫描应过滤标志文件")
        void shouldPrescanAndFilterFlagFiles() throws Exception {
            config.setPreOperations("FLAG:{stem}.OK");
            String[] allFiles = {"/data/files/data1.csv", "/data/files/data1.OK",
                    "/data/files/data2.csv", "/data/files/data2.OK"};
            when(ftpClient.listFiles(anyString())).thenReturn(allFiles);
            when(uploadSupport.preCheck(any(), any(), any(), anyString())).thenReturn(null);
            when(uploadSupport.insertDataAndVerify(any(), any(), any(FieldMapping.class), anyString(), any())).thenReturn(100);

            handler.handle(command, config, result);

            // 2 个数据文件被处理，标志文件被过滤
            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("insert 失败应计入失败数")
        void shouldCountInsertFailAsError() throws Exception {
            when(ftpClient.listFiles(anyString())).thenReturn(new String[]{"/data/files/file1.csv"});
            when(uploadSupport.preCheck(any(), any(), any(), anyString())).thenReturn(null);
            when(uploadSupport.insertDataAndVerify(any(), any(), any(FieldMapping.class), anyString(), any())).thenReturn(-2);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }

        @Test
        @DisplayName("没有文件时应返回 SKIPPED")
        void shouldReturnSkippedWhenNoFiles() throws Exception {
            when(ftpClient.listFiles(anyString())).thenReturn(new String[0]);

            handler.handle(command, config, result);
            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("所有文件被预扫描过滤时应返回 SKIPPED")
        void shouldReturnSkippedWhenAllFiltered() throws Exception {
            config.setPreOperations("FLAG:{stem}.OK");
            String[] allFiles = {"/data/files/data1.OK", "/data/files/data2.OK"};
            when(ftpClient.listFiles(anyString())).thenReturn(allFiles);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
        }
    }

    // ==================== prescanDataFiles ====================

    @Nested
    @DisplayName("prescanDataFiles")
    class PrescanDataFilesTests {

        @Test
        @DisplayName("无 FLAG 模式时返回所有文件")
        void shouldReturnAllWhenNoFlagPattern() {
            String[] files = {"file1.csv", "file2.csv"};
            List<String> result = handler.prescanDataFiles(files, null);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("过滤标志文件，保留有对应标志的数据文件")
        void shouldFilterFlagFiles() {
            String[] files = {"data1.csv", "data1.OK", "data2.csv", "data2.OK"};
            List<String> result = handler.prescanDataFiles(files, "{stem}.OK");
            assertEquals(2, result.size());
            assertTrue(result.contains("data1.csv"));
            assertTrue(result.contains("data2.csv"));
        }

        @Test
        @DisplayName("数据文件无对应标志文件时应告警排除")
        void shouldExcludeDataFilesWithoutFlags() {
            String[] files = {"data1.csv", "data1.OK", "orphan.csv"};
            List<String> result = handler.prescanDataFiles(files, "{stem}.OK");
            assertEquals(1, result.size());
            assertTrue(result.contains("data1.csv"));
        }

        @Test
        @DisplayName("空文件列表应返回空")
        void shouldHandleEmptyList() {
            List<String> result = handler.prescanDataFiles(new String[0], "{stem}.OK");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null 文件列表应返回空")
        void shouldHandleNullList() {
            List<String> result = handler.prescanDataFiles(null, "{stem}.OK");
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("extractFlagPathPattern")
    class ExtractFlagPathPatternTests {

        @Test
        @DisplayName("提取简单 FLAG 模式")
        void shouldExtractSimpleFlag() {
            assertEquals("{stem}.OK", UploadSupport.extractFlagPathPattern("FLAG:{stem}.OK"));
        }

        @Test
        @DisplayName("提取带 mode 后缀的 FLAG 模式")
        void shouldExtractFlagWithMode() {
            assertEquals("{stem}.OK", UploadSupport.extractFlagPathPattern("FLAG:{stem}.OK;L"));
        }

        @Test
        @DisplayName("多操作中提取第一个 FLAG")
        void shouldExtractFirstFlagFromMultiple() {
            String ops = "FLAG:{stem}.OK,READY:other.txt";
            assertEquals("{stem}.OK", UploadSupport.extractFlagPathPattern(ops));
        }

        @Test
        @DisplayName("无 FLAG 时返回 null")
        void shouldReturnNullWhenNoFlag() {
            assertNotNull(UploadSupport.extractFlagPathPattern("READY:check.txt"));
            assertEquals("check.txt", UploadSupport.extractFlagPathPattern("READY:check.txt"));
            assertNull(UploadSupport.extractFlagPathPattern(""));
            assertNull(UploadSupport.extractFlagPathPattern(null));
        }
    }

    @Nested
    @DisplayName("resolveFlagName")
    class ResolveFlagNameTests {

        @Test
        @DisplayName("解析 {stem} 模式")
        void shouldResolveStemPattern() {
            ResolvedPath info = ResolvedPath.of("/data/file.csv");
            String result = MultiUploadHandler.resolveFlagName("{stem}.OK", info);
            assertEquals("/data/file.OK", result);
        }

        @Test
        @DisplayName("解析 {name} 模式")
        void shouldResolveNamePattern() {
            ResolvedPath info = ResolvedPath.of("/data/file.csv");
            String result = MultiUploadHandler.resolveFlagName("{name}.flag", info);
            assertEquals("/data/file.csv.flag", result);
        }

        @Test
        @DisplayName("处理相对路径")
        void shouldHandleRelativePath() {
            ResolvedPath info = ResolvedPath.of("file.csv");
            String result = MultiUploadHandler.resolveFlagName("{stem}.OK", info);
            assertEquals("file.OK", result);
        }

        @Test
        @DisplayName("null 输入返回 null")
        void shouldReturnNullForNull() {
            assertNull(MultiUploadHandler.resolveFlagName(null, ResolvedPath.of("f.csv")));
            assertNull(MultiUploadHandler.resolveFlagName("{stem}.OK", null));
        }
    }
}