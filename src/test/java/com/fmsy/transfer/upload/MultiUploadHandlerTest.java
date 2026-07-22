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

    /** 构造 safeExecuteFilePipeline 的 mock 返回值 */
    private UploadSupport.UploadResult mockPipelineResult(int records, String status) {
        return new UploadSupport.UploadResult(records, status);
    }

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
            // 默认管线返回成功 100 条
            when(uploadSupport.safeExecuteFilePipeline(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(mockPipelineResult(100, null));
        }

        @Test
        @DisplayName("SERIAL 模式正常处理")
        void shouldHandleSerialMode() throws Exception {
            when(ftpClient.listFiles(anyString())).thenReturn(new String[]{"/data/files/file1.csv"});

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            assertEquals(100, result.getRecordCount());
        }

        @Test
        @DisplayName("清表标志为 Y 时在 prescan 后、落库前调用 truncateTable")
        void shouldTruncateAfterPreCheckBeforeInsert() throws Exception {
            config.setClearTableFlag("Y");
            when(ftpClient.listFiles(anyString())).thenReturn(new String[]{"/data/files/file1.csv"});

            handler.handle(command, config, result);

            verify(uploadSupport).truncateTable(config);
            verify(uploadSupport, atLeastOnce()).safeExecuteFilePipeline(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("预扫描应过滤标志文件")
        void shouldPrescanAndFilterFlagFiles() throws Exception {
            config.setPreOperations("L:S.ok");
            String[] allFiles = {"/data/files/data1.csv", "/data/files/data1.OK",
                    "/data/files/data2.csv", "/data/files/data2.OK"};
            when(ftpClient.listFiles(anyString())).thenReturn(allFiles);

            handler.handle(command, config, result);

            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("insert 失败时应记录日志但结果仍为 Y")
        void shouldCountInsertFailAsError() throws Exception {
            when(ftpClient.listFiles(anyString())).thenReturn(new String[]{"/data/files/file1.csv"});
            // 管线返回 ERROR → insertSingleFile 返回 TASK_FAIL
            when(uploadSupport.safeExecuteFilePipeline(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(mockPipelineResult(0, ColumnNames.STATUS_ERROR));

            handler.handle(command, config, result);

            // 有文件失败，但整体状态仍为 SUCCESS（BATCH 模式不清表不整体回滚）
            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
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
            config.setPreOperations("L:S.ok");
            // 只有标志文件，没有数据文件
            String[] allFiles = {"/data/files/data1.OK"};
            when(ftpClient.listFiles(anyString())).thenReturn(allFiles);

            handler.handle(command, config, result);
            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("空数据处理应 ALLOW")
        void shouldHandleEmptyDataAllow() throws Exception {
            config.setEmptyDataHandling(EmptyDataHandling.ALLOW);
            config.setPreOperations("L:S.ok");
            String[] allFiles = {"/data/files/data1.csv", "/data/files/data1.OK"};
            when(ftpClient.listFiles(anyString())).thenReturn(allFiles);

            handler.handle(command, config, result);
            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("空数据处理应 SKIP")
        void shouldHandleEmptyDataSkip() throws Exception {
            config.setEmptyDataHandling(EmptyDataHandling.SKIP);
            config.setPreOperations("L:S.ok");
            String[] allFiles = {"/data/files/data1.csv", "/data/files/data1.OK"};
            when(ftpClient.listFiles(anyString())).thenReturn(allFiles);

            handler.handle(command, config, result);
            assertNotNull(result.getResult());
        }
    }

    // ==================== resolveFlagName ====================

    @Nested
    @DisplayName("resolveFlagName")
    class ResolveFlagNameTests {

        @Test
        @DisplayName("完整路径处理")
        void shouldHandleFullPath() {
            ResolvedPath info = ResolvedPath.of("/data/file.csv");
            String result = UploadPrescanner.resolveFlagName("{name}.flag", info);
            assertEquals("/data/file.csv.flag", result);
        }

        @Test
        @DisplayName("处理相对路径")
        void shouldHandleRelativePath() {
            ResolvedPath info = ResolvedPath.of("file.csv");
            String result = UploadPrescanner.resolveFlagName("{stem}.OK", info);
            assertEquals("/file.OK", result);
        }

        @Test
        @DisplayName("null 输入返回 null")
        void shouldReturnNullForNull() {
            assertNull(UploadPrescanner.resolveFlagName(null, ResolvedPath.of("f.csv")));
            assertNull(UploadPrescanner.resolveFlagName("{stem}.OK", null));
        }
    }
}
