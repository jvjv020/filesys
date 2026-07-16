package com.fmsy.transfer.upload;

import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.exception.FlagCheckException;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SingleUploadHandler 重构后测试 — 使用纯方法组合。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SingleUploadHandler Tests")
class SingleUploadHandlerTest {

    @Mock
    private DetailRepository detailRepository;

    @Mock
    private UploadSupport uploadSupport;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private ConverterFactory converterFactory;

    @Mock
    private FileConverter fileConverter;

    @Mock
    private FieldMappingBuilder fieldMappingBuilder;

    private SingleUploadHandler handler;

    private Command command;
    private TransferConfig config;
    private Result result;
    private ResolvedPath fileInfo;

    @BeforeEach
    void setUp() throws Exception {
        handler = new SingleUploadHandler(detailRepository, uploadSupport, transferSupport, fieldMappingBuilder);

        command = new Command();
        command.setId(1L);
        command.setCategoryCode("CAT001");
        command.setControlCode("CTRL001");
        command.setAuditCount(100);

        config = new TransferConfig();
        config.setFtpName("ftp1");
        config.setFilePath("/data/{YYYYMMDD}/file.csv");
        config.setParserType("CSV");
        config.setTableName("mytable");
        config.setDbName("DB1");

        result = new Result();
        fileInfo = ResolvedPath.of("/data/20260615/file.csv");
    }

    // ==================== preCheck 返回 SKIPPED ====================

    @Nested
    @DisplayName("handle - preCheck 返回 SKIPPED（标志文件不存在）")
    class HandlePreCheckReturnsFalse {

        @Test
        @DisplayName("应设置 SKIPPED 状态")
        void shouldSetSkippedWhenPreCheckReturnsFalse() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            doAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(mock(com.fmsy.ftp.FtpClient.class));
                    }).when(transferSupport).executeWithClient(eq("ftp1"), any());
            when(uploadSupport.preCheck(any(), any(), any(), anyString()))
                    .thenReturn(new UploadSupport.UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
            assertEquals(0, result.getRecordCount());
        }
    }

    // ==================== 成功流程 ====================

    @Nested
    @DisplayName("handle - 成功流程")
    class HandleSuccessFlow {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            doAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(mock(com.fmsy.ftp.FtpClient.class));
                    }).when(transferSupport).executeWithClient(eq("ftp1"), any());
            when(uploadSupport.preCheck(any(), any(), any(), anyString())).thenReturn(null);
            when(uploadSupport.insertDataAndVerify(any(), any(), any(ResolvedPath.class), any(), anyString(), any())).thenReturn(100);
        }

        @Test
        @DisplayName("应设置 SUCCESS 状态和记录数")
        void shouldSetSuccessWithRecordCount() throws Exception {
            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            assertEquals(100, result.getRecordCount());
        }

        @Test
        @DisplayName("清表标志为 Y 时在 preCheck 后、落库前调用 truncateTable")
        void shouldTruncateAfterPreCheckBeforeInsert() throws Exception {
            config.setClearTableFlag("Y");

            handler.handle(command, config, result);

            // 验证执行顺序：preCheck → truncate → insert（前稽核已合并到 insertDataAndVerify 中）
            verify(uploadSupport).preCheck(any(), any(), any(), anyString());
            verify(uploadSupport).truncateTable(config);
            verify(uploadSupport).insertDataAndVerify(any(), any(), any(ResolvedPath.class), any(), anyString(), any());
            verify(uploadSupport).postProcess(any(), any(), any(ResolvedPath.class), eq(100));
        }
    }

    // ==================== 异常流程 ====================

    @Nested
    @DisplayName("handle - 异常流程")
    class HandleExceptionFlow {

        @BeforeEach
        void setup() {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
        }

        @Test
        @DisplayName("insertDataAndVerify 失败应设置 ERROR 并迁文件")
        @SuppressWarnings("unchecked")
        void shouldSetErrorOnInsertFailure() throws Exception {
            doAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(mock(com.fmsy.ftp.FtpClient.class));
                    }).when(transferSupport).executeWithClient(eq("ftp1"), any());
            when(uploadSupport.preCheck(any(), any(), any(), anyString())).thenReturn(null);
            when(uploadSupport.insertDataAndVerify(any(), any(), any(ResolvedPath.class), any(), anyString(), any()))
                    .thenThrow(new RuntimeException("Pre-audit failed: expected 100, got 50"));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }

        @Test
        @DisplayName("FlagCheckException 应设置 ERROR 并迁文件")
        void shouldSetErrorOnFlagCheckException() throws Exception {
            doAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(mock(com.fmsy.ftp.FtpClient.class));
                    }).when(transferSupport).executeWithClient(eq("ftp1"), any());
            when(uploadSupport.preCheck(any(), any(), any(), anyString()))
                    .thenThrow(new FlagCheckException("FLAG mismatch"));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }
    }

    // ==================== 空文件 ====================

    @Nested
    @DisplayName("handle - 空文件场景")
    class HandleEmptyFile {

        @Test
        @DisplayName("空文件且 handling=ALLOW，应 SUCCESS")
        void shouldBeSuccessWhenEmptyFileAndAllow() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            doAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(mock(com.fmsy.ftp.FtpClient.class));
                    }).when(transferSupport).executeWithClient(eq("ftp1"), any());
            when(uploadSupport.preCheck(any(), any(), any(), anyString())).thenReturn(null);
            when(uploadSupport.insertDataAndVerify(any(), any(), any(ResolvedPath.class), any(), anyString(), any())).thenReturn(0);

            config.setEmptyDataHandling(EmptyDataHandling.ALLOW);
            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            assertEquals(0, result.getRecordCount());
        }
    }

    // ==================== BATCH 模式 ====================

    @Nested
    @DisplayName("handle - BATCH mode（明细表指定文件）")
    class HandleBatchMode {

        private Map<String, Object> detail;

        @BeforeEach
        void setupBatch() {
            command.setCommandType(CommandType.BATCH);
            config.setNodeId("node1");

            detail = new HashMap<>();
            detail.put(ColumnNames.DETAIL_ID, 100L);
            detail.put(ColumnNames.FILE_NAME, "data001.csv");
            detail.put(ColumnNames.FIELD_NAME, "REGION");
            detail.put(ColumnNames.FIELD_VALUE, "EAST");
        }

        @Test
        @DisplayName("明细表提供一个文件，应成功落库并更新明细状态为 SUCCESS")
        void shouldUploadWithDetailAndUpdateStatus() throws Exception {
            when(detailRepository.findUploadDetails(1L, ColumnNames.STATUS_EMPTY))
                    .thenReturn(Collections.singletonList(detail));
            Map<String, String> context = new HashMap<>();
            context.put("FILE_NAME", "data001.csv");
            when(transferSupport.buildContext(command, "REGION", "EAST")).thenReturn(context);
            when(transferSupport.resolveFilePath(config.getFilePath(), context))
                    .thenReturn(ResolvedPath.of("/data/20260615/data001.csv"));
            doAnswer(inv -> {
                TransferSupport.FtpClientCallback<?> cb = inv.getArgument(1);
                return cb.run(mock(com.fmsy.ftp.FtpClient.class));
            }).when(transferSupport).executeWithClient(eq("ftp1"), any());
            when(uploadSupport.preCheck(any(), any(), any(), anyString())).thenReturn(null);
            when(uploadSupport.insertDataAndVerify(any(), any(), any(ResolvedPath.class), any(), anyString(), any()))
                    .thenReturn(100);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            assertEquals(100, result.getRecordCount());
            verify(detailRepository).updateStatus(100L, ColumnNames.STATUS_SUCCESS, "node1");
        }

        @Test
        @DisplayName("明细表为空应返回 SKIPPED")
        void shouldSkipWhenNoDetails() throws Exception {
            when(detailRepository.findUploadDetails(1L, ColumnNames.STATUS_EMPTY))
                    .thenReturn(Collections.emptyList());

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
            verify(detailRepository, never()).updateStatus(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("FILE_NAME 为空应返回 SKIPPED 并更新明细状态")
        void shouldSkipWhenEmptyFileName() throws Exception {
            detail.put(ColumnNames.FILE_NAME, "");
            when(detailRepository.findUploadDetails(1L, ColumnNames.STATUS_EMPTY))
                    .thenReturn(Collections.singletonList(detail));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
            verify(detailRepository).updateStatus(100L, ColumnNames.STATUS_SKIPPED, "node1");
        }

        @Test
        @DisplayName("preCheck 失败应设置 SKIPPED 并更新明细状态")
        void shouldSkipWhenPreCheckFails() throws Exception {
            when(detailRepository.findUploadDetails(1L, ColumnNames.STATUS_EMPTY))
                    .thenReturn(Collections.singletonList(detail));
            Map<String, String> context = new HashMap<>();
            when(transferSupport.buildContext(command, "REGION", "EAST")).thenReturn(context);
            when(transferSupport.resolveFilePath(anyString(), any(Map.class)))
                    .thenReturn(ResolvedPath.of("/data/data001.csv"));
            doAnswer(inv -> {
                TransferSupport.FtpClientCallback<?> cb = inv.getArgument(1);
                return cb.run(mock(com.fmsy.ftp.FtpClient.class));
            }).when(transferSupport).executeWithClient(eq("ftp1"), any());
            when(uploadSupport.preCheck(any(), any(), any(), anyString()))
                    .thenReturn(new UploadSupport.UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
            verify(detailRepository).updateStatus(100L, ColumnNames.STATUS_SKIPPED, "node1");
        }

        @Test
        @DisplayName("insert 失败应设置 ERROR 并更新明细状态")
        void shouldSetErrorOnInsertFailure() throws Exception {
            when(detailRepository.findUploadDetails(1L, ColumnNames.STATUS_EMPTY))
                    .thenReturn(Collections.singletonList(detail));
            Map<String, String> context = new HashMap<>();
            when(transferSupport.buildContext(command, "REGION", "EAST")).thenReturn(context);
            when(transferSupport.resolveFilePath(anyString(), any(Map.class)))
                    .thenReturn(ResolvedPath.of("/data/data001.csv"));
            doAnswer(inv -> {
                TransferSupport.FtpClientCallback<?> cb = inv.getArgument(1);
                return cb.run(mock(com.fmsy.ftp.FtpClient.class));
            }).when(transferSupport).executeWithClient(eq("ftp1"), any());
            when(uploadSupport.preCheck(any(), any(), any(), anyString())).thenReturn(null);
            when(uploadSupport.insertDataAndVerify(any(), any(), any(ResolvedPath.class), any(), anyString(), any()))
                    .thenThrow(new RuntimeException("Insert failed"));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            verify(detailRepository).updateStatus(100L, ColumnNames.STATUS_ERROR, "node1");
        }

        @Test
        @DisplayName("FlagCheckException 应设置 ERROR 并更新明细状态")
        void shouldSetErrorOnFlagCheckException() throws Exception {
            when(detailRepository.findUploadDetails(1L, ColumnNames.STATUS_EMPTY))
                    .thenReturn(Collections.singletonList(detail));
            Map<String, String> context = new HashMap<>();
            when(transferSupport.buildContext(command, "REGION", "EAST")).thenReturn(context);
            when(transferSupport.resolveFilePath(anyString(), any(Map.class)))
                    .thenReturn(ResolvedPath.of("/data/data001.csv"));
            doAnswer(inv -> {
                TransferSupport.FtpClientCallback<?> cb = inv.getArgument(1);
                return cb.run(mock(com.fmsy.ftp.FtpClient.class));
            }).when(transferSupport).executeWithClient(eq("ftp1"), any());
            when(uploadSupport.preCheck(any(), any(), any(), anyString()))
                    .thenThrow(new FlagCheckException("FLAG mismatch"));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            verify(detailRepository).updateStatus(100L, ColumnNames.STATUS_ERROR, "node1");
        }
    }
}