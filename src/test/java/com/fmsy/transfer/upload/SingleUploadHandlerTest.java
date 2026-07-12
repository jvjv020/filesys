package com.fmsy.transfer.upload;

import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.CommandType;
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
                    .thenReturn(100);

            // Phase 2: readAndInsert
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(ftpClient.getInputStream(fileInfo.fullPath())).thenReturn(mock(InputStream.class));
            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForUpload(config, null)).thenReturn(mapping);

            Iterator<List<Map<String, Object>>> iterator = Collections.emptyIterator();
            // Use a mock CloseableIterator that returns no data
            when(fileConverter.parse(any(InputStream.class), any())).thenReturn(iterator);
            when(uploadSupport.insertBatchInTx(eq(config), any(), eq(mapping), anyBoolean())).thenReturn(100);

            // Phase 2 continued: postAudit must succeed for readAndInsert to return count >= 0
            when(uploadSupport.postAudit(eq(config), anyInt(), anyInt())).thenReturn(true);

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
                    .thenReturn(100);

            // Second executeWithClient for readAndInsert
            when(ftpClient.getInputStream(fileInfo.fullPath())).thenReturn(mock(InputStream.class));
            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForUpload(config, null)).thenReturn(mapping);

            Iterator<List<Map<String, Object>>> iterator = Collections.emptyIterator();
            when(fileConverter.parse(any(InputStream.class), any())).thenReturn(iterator);
            when(uploadSupport.insertBatchInTx(eq(config), any(), eq(mapping), anyBoolean())).thenReturn(95);

            handler.handle(command, config, result);

            assertEquals(0, result.getRecordCount());
            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }
    }
}
