package com.fmsy.transfer.download;

import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.OutputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SingleDownloadHandler Tests")
class SingleDownloadHandlerTest {

    @Mock
    private FieldMappingBuilder fieldMappingBuilder;

    @Mock
    private DownloadSupport downloadSupport;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private ParallelFileGenerator parallelFileGenerator;

    @Mock
    private ConverterFactory converterFactory;

    @Mock
    private FtpClient ftpClient;

    @Mock
    private FileConverter fileConverter;

    @InjectMocks
    private SingleDownloadHandler handler;

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
        config.setFilePath("/data/out.csv");
        config.setParserType("CSV");
        config.setTableName("mytable");
        config.setDbName("DB1");
        config.setOverwriteFlag("Y");

        result = new Result();
        fileInfo = ResolvedPath.of("/data/out.csv");
    }

    @Nested
    @DisplayName("handle - phase 1 failure")
    class HandlePhase1Failure {

        @Test
        @DisplayName("should set skipped when pre-check fails")
        void shouldSetSkippedWhenPreCheckFails() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(false);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
            assertEquals("Pre-check failed", result.getDescription());
        }

        @Test
        @DisplayName("should set error when overwrite denied")
        void shouldSetErrorWhenOverwriteDenied() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);
            when(downloadSupport.checkOverwriteAllowed(ftpClient, fileInfo.fullPath(),
                    config.getOverwriteFlag())).thenReturn(false);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }
    }

    @Nested
    @DisplayName("handle - pre-audit failure")
    class HandlePreAuditFailure {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);
            when(downloadSupport.checkOverwriteAllowed(any(), anyString(), anyString())).thenReturn(true);
        }

        @Test
        @DisplayName("should set skipped when pre-audit fails")
        void shouldSetSkippedWhenPreAuditFails() throws Exception {
            when(downloadSupport.preAudit(config, 100)).thenReturn(-1);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
        }

        @Test
        @DisplayName("should count records when auditCount is null")
        void shouldCountRecordsWhenAuditCountIsNull() throws Exception {
            command.setAuditCount(null);
            when(downloadSupport.countRecords(config)).thenReturn(50);

            handler.handle(command, config, result);

            verify(downloadSupport).countRecords(config);
        }
    }

    @Nested
    @DisplayName("handle - empty data")
    class HandleEmptyData {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);
            when(downloadSupport.checkOverwriteAllowed(any(), anyString(), anyString())).thenReturn(true);
            when(downloadSupport.preAudit(config, 100)).thenReturn(0);
        }

        @Test
        @DisplayName("should skip when empty data and handling is SKIP")
        void shouldSkipWhenEmptyAndHandlingIsSkip() throws Exception {
            config.setEmptyDataHandling(EmptyDataHandling.SKIP);
            when(transferSupport.handleEmptyData(0, EmptyDataHandling.SKIP)).thenReturn(false);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
        }

        @Test
        @DisplayName("should error when empty data and handling is ERROR")
        void shouldErrorWhenEmptyAndHandlingIsError() throws Exception {
            config.setEmptyDataHandling(EmptyDataHandling.ERROR);
            when(transferSupport.handleEmptyData(0, EmptyDataHandling.ERROR)).thenReturn(false);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }
    }

    @Nested
    @DisplayName("handle - successful download")
    class HandleSuccessfulDownload {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);

            // Phase 1
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);
            when(downloadSupport.checkOverwriteAllowed(ftpClient, fileInfo.fullPath(),
                    config.getOverwriteFlag())).thenReturn(true);

            // Phase 2
            when(downloadSupport.preAudit(config, 100)).thenReturn(100);
            when(transferSupport.handleEmptyData(100, config.getEmptyDataHandling())).thenReturn(true);
            when(downloadSupport.postAudit(config, fileInfo.fullPath(), 100)).thenReturn(true);

            // Phase 3
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForDownload(config)).thenReturn(mapping);
            when(ftpClient.getOutputStream(fileInfo.fullPath())).thenReturn(mock(OutputStream.class));
            when(parallelFileGenerator.generate(any(OutputStream.class), eq(config),
                    eq(fileConverter), eq(mapping), eq(100L))).thenReturn(100);
            when(downloadSupport.countRecords(config)).thenReturn(100);
        }

        @Test
        @DisplayName("should complete successful download")
        void shouldCompleteSuccessfulDownload() throws Exception {
            handler.handle(command, config, result);

            assertEquals(100, result.getRecordCount());
            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        }

        @Test
        @DisplayName("should call postProcess on success")
        void shouldCallPostProcess() throws Exception {
            handler.handle(command, config, result);

            verify(transferSupport).postProcess(eq(ftpClient), eq(config), eq(fileInfo), eq(100));
            verify(ftpClient).completePendingCommand();
        }
    }

    @Nested
    @DisplayName("handle - post-audit failure")
    class HandlePostAuditFailure {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);

            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);
            when(downloadSupport.checkOverwriteAllowed(any(), anyString(), anyString())).thenReturn(true);
            when(downloadSupport.preAudit(config, 100)).thenReturn(100);
            when(transferSupport.handleEmptyData(100, config.getEmptyDataHandling())).thenReturn(true);

            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForDownload(config)).thenReturn(mapping);
            when(ftpClient.getOutputStream(fileInfo.fullPath())).thenReturn(mock(OutputStream.class));
            when(parallelFileGenerator.generate(any(OutputStream.class), eq(config),
                    eq(fileConverter), eq(mapping), eq(100L))).thenReturn(100);
            when(downloadSupport.countRecords(config)).thenReturn(100);
        }

        @Test
        @DisplayName("should rollback on post-audit failure")
        void shouldRollbackOnPostAuditFailure() throws Exception {
            when(downloadSupport.postAudit(config, fileInfo.fullPath(), 100)).thenReturn(false);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }

        @Test
        @DisplayName("should skip post-audit when auditCount is null")
        void shouldSkipPostAuditWhenAuditCountNull() throws Exception {
            command.setAuditCount(null);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            verify(downloadSupport, never()).postAudit(any(), anyString(), anyInt());
        }
    }
}
