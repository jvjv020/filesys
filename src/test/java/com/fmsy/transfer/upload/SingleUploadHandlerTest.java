package com.fmsy.transfer.upload;

import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.CommandType;
import com.fmsy.exception.FlagCheckException;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SingleUploadHandler Tests")
class SingleUploadHandlerTest {

    @Mock
    private FieldMappingBuilder fieldMappingBuilder;

    @Mock
    private UploadSupport uploadSupport;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private ConverterFactory converterFactory;

    @Mock
    private FtpClient ftpClient;

    @Mock
    private FileConverter fileConverter;

    @InjectMocks
    private SingleUploadHandler handler;

    private Command command;
    private TransferConfig config;
    private Result result;
    private ResolvedPath fileInfo;

    @BeforeEach
    void setUp() {
        command = new Command();
        command.setId(1L);
        command.setCategoryCode("CAT001");
        command.setControlCode("CTRL001");
        command.setCommandType(CommandType.SERIAL);
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

    @Nested
    @DisplayName("handle - pre-check failure")
    class HandlePreCheckFailure {

        @Test
        @DisplayName("should set skipped outcome when pre-check fails")
        void shouldSetSkippedWhenPreCheckFails() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(false);

            handler.handle(command, config, result);

            assertEquals(0, result.getRecordCount());
            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
            verifyNoInteractions(converterFactory, uploadSupport);
        }
    }

    @Nested
    @DisplayName("handle - pre-audit failure")
    class HandlePreAuditFailure {

        @BeforeEach
        void setupPreCheckOk() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(uploadSupport.preAudit(command.getAuditCount(), config, fileInfo.fullPath(), fileConverter))
                    .thenReturn(-1);
        }

        @Test
        @DisplayName("should set error outcome and move file to error dir")
        void shouldSetErrorAndMoveToErrorDir() throws Exception {
            handler.handle(command, config, result);

            assertEquals(0, result.getRecordCount());
            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            verify(ftpClient).moveToErrorDir(fileInfo.fullPath());
        }
    }

    @Nested
    @DisplayName("handle - successful flow")
    class HandleSuccessfulFlow {

        @BeforeEach
        void setupSuccessfulFlow() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);

            // Phase 1: preCheck
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);

            // Phase 2: converter
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(uploadSupport.preAudit(command.getAuditCount(), config, fileInfo.fullPath(), fileConverter))
                    .thenReturn(0);

            // Phase 2: readAndInsert — uses insertAndVerifyInTx internally
            when(ftpClient.getInputStream(fileInfo.fullPath())).thenReturn(mock(InputStream.class));
            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForUpload(config, null)).thenReturn(mapping);

            // insertAndVerifyInTx returns 100 (success), no error set on result
            when(uploadSupport.insertAndVerifyInTx(eq(config), any(CloseableIterator.class),
                    eq(mapping), anyBoolean(), any(Result.class))).thenReturn(100);

            // Phase 3: postProcess
            doNothing().when(transferSupport).postProcess(any(), eq(config), any(), anyInt());
        }

        @Test
        @DisplayName("should complete full flow successfully")
        void shouldCompleteFullFlow() throws Exception {
            handler.handle(command, config, result);

            assertEquals(100, result.getRecordCount());
            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        }

        @Test
        @DisplayName("should call postProcess on success")
        void shouldCallPostProcessOnSuccess() throws Exception {
            handler.handle(command, config, result);

            verify(transferSupport).postProcess(eq(ftpClient), eq(config), eq(fileInfo), eq(100));
        }
    }

    @Nested
    @DisplayName("handle - post-audit failure")
    class HandlePostAuditFailure {

        @Test
        @DisplayName("should set error outcome when post-audit fails in non-ERROR mode")
        void shouldSetErrorWhenPostAuditFails() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);

            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(uploadSupport.preAudit(command.getAuditCount(), config, fileInfo.fullPath(), fileConverter))
                    .thenReturn(0);

            // readAndInsert: insertAndVerifyInTx returns 100 BUT sets result to ERROR
            when(ftpClient.getInputStream(fileInfo.fullPath())).thenReturn(mock(InputStream.class));
            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForUpload(config, null)).thenReturn(mapping);

            when(uploadSupport.insertAndVerifyInTx(eq(config), any(CloseableIterator.class),
                    eq(mapping), anyBoolean(), any(Result.class)))
                    .thenAnswer(invocation -> {
                        Result r = invocation.getArgument(4);
                        r.setOutcome(0, ColumnNames.STATUS_ERROR, "Post-audit failed: file records=95, inserted=100");
                        return 100;
                    });

            handler.handle(command, config, result);

            assertEquals(0, result.getRecordCount());
            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }
    }

    @Nested
    @DisplayName("handle - Phase 3 postProcess exception")
    class HandlePhase3Exception {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(uploadSupport.preAudit(any(), any(), anyString(), any())).thenReturn(0);
            when(ftpClient.getInputStream(fileInfo.fullPath())).thenReturn(mock(InputStream.class));
            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForUpload(config, null)).thenReturn(mapping);
            when(uploadSupport.insertAndVerifyInTx(eq(config), any(CloseableIterator.class),
                    eq(mapping), anyBoolean(), any(Result.class))).thenReturn(100);
            doThrow(new RuntimeException("FTP write timeout"))
                    .when(transferSupport).postProcess(eq(ftpClient), eq(config), eq(fileInfo), eq(100));
        }

        @Test
        @DisplayName("should set error with accurate record count and description when postProcess fails")
        void shouldSetErrorWithRecordCountOnPostProcessFailure() throws Exception {
            handler.handle(command, config, result);

            assertEquals(100, result.getRecordCount());
            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            assertTrue(result.getDescription().contains("uploaded successfully"),
                    "Description should mention successful upload");
            assertTrue(result.getDescription().contains("100 records"),
                    "Description should include record count");
            assertTrue(result.getDescription().contains("FTP write timeout"),
                    "Description should include original exception message");
        }
    }

    @Nested
    @DisplayName("handle - FlagCheckException from preCheck")
    class HandlePreCheckFlagCheckException {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo))
                    .thenThrow(new FlagCheckException("expected '100' == actual '50' (metric=L)"));
        }

        @Test
        @DisplayName("should propagate FlagCheckException for Orchestrator to mark ERROR")
        void shouldPropagateFlagCheckException() {
            assertThrows(FlagCheckException.class,
                    () -> handler.handle(command, config, result),
                    "FlagCheckException should propagate to Orchestrator");
            // 异常传播到 AbstractTransferOrchestrator.execute() 的 catch 块,
            // 会调用 result.failWith(e) → STATUS_ERROR + 异常 message
            verifyNoInteractions(converterFactory, uploadSupport);
        }
    }

    @Nested
    @DisplayName("handle - empty file scenarios")
    class HandleEmptyFile {

        private FieldMapping mapping;

        @BeforeEach
        void setupCommon() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(uploadSupport.preAudit(any(), any(), anyString(), any())).thenReturn(0);
            when(ftpClient.getInputStream(fileInfo.fullPath())).thenReturn(mock(InputStream.class));
            mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForUpload(config, null)).thenReturn(mapping);
            doNothing().when(transferSupport).postProcess(any(), eq(config), any(), anyInt());
        }

        @Test
        @DisplayName("should complete with 0 records when file is empty and handling is ALLOW")
        void shouldCompleteWithZeroRecordsWhenEmptyAllow() throws Exception {
            when(uploadSupport.insertAndVerifyInTx(eq(config), any(CloseableIterator.class),
                    eq(mapping), anyBoolean(), any(Result.class))).thenReturn(0);
            config.setEmptyDataHandling(com.fmsy.enums.EmptyDataHandling.ALLOW);

            handler.handle(command, config, result);

            assertEquals(0, result.getRecordCount());
            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            verify(transferSupport).postProcess(any(), eq(config), any(), eq(0));
        }

        @Test
        @DisplayName("should propagate exception when file is empty and handling is ERROR")
        void shouldPropagateExceptionWhenEmptyError() throws Exception {
            when(uploadSupport.insertAndVerifyInTx(eq(config), any(CloseableIterator.class),
                    eq(mapping), anyBoolean(), any(Result.class)))
                    .thenThrow(new RuntimeException("Empty data handling: ERROR"));

            assertThrows(RuntimeException.class,
                    () -> handler.handle(command, config, result),
                    "RuntimeException should propagate to Orchestrator");
            // Orchestrator 会捕获并设置 STATUS_ERROR
        }
    }

    @Nested
    @DisplayName("handle - moveToErrorDir exception safety")
    class HandleMoveToErrorDirException {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(uploadSupport.preAudit(command.getAuditCount(), config, fileInfo.fullPath(), fileConverter))
                    .thenReturn(-1);
            // moveToErrorDir throws exception
            when(ftpClient.moveToErrorDir(anyString())).thenThrow(new RuntimeException("FTP move failed"));
        }

        @Test
        @DisplayName("should still set error outcome when moveToErrorDir throws")
        void shouldSetErrorEvenWhenMoveToErrorDirThrows() throws Exception {
            handler.handle(command, config, result);

            assertEquals(0, result.getRecordCount());
            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            // 异常被 moveToErrorDir 内部 catch 住，不影响流程
        }
    }

    @Nested
    @DisplayName("handle - truncateFirst behavior")
    class HandleTruncateFirst {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(uploadSupport.preAudit(any(), any(), anyString(), any())).thenReturn(0);
            when(ftpClient.getInputStream(fileInfo.fullPath())).thenReturn(mock(InputStream.class));
            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForUpload(config, null)).thenReturn(mapping);
            when(uploadSupport.insertAndVerifyInTx(eq(config), any(CloseableIterator.class),
                    eq(mapping), eq(true), any(Result.class))).thenReturn(100);
            doNothing().when(transferSupport).postProcess(any(), eq(config), any(), anyInt());
        }

        @Test
        @DisplayName("should pass truncateFirst=true to insertAndVerifyInTx when clearTableFlag=Y")
        void shouldPassTruncateFirstTrue() throws Exception {
            config.setClearTableFlag("Y");

            handler.handle(command, config, result);

            verify(uploadSupport).insertAndVerifyInTx(
                    eq(config), any(CloseableIterator.class),
                    any(FieldMapping.class), eq(true), any(Result.class));
            assertEquals(100, result.getRecordCount());
            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        }
    }
}
