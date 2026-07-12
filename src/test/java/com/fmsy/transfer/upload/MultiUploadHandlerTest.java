package com.fmsy.transfer.upload;

import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.exception.TransferException;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.InputStream;
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
    private FieldMappingBuilder fieldMappingBuilder;

    @Mock
    private UploadSupport uploadSupport;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private FtpPool ftpPool;

    @Mock
    private ConverterFactory converterFactory;

    @Mock
    private FtpClient ftpClient;

    @Mock
    private FileConverter fileConverter;

    private IntFunction<ExecutorService> batchExecutorFactory;
    private MultiUploadHandler handler;

    private Command command;
    private TransferConfig config;
    private Result result;

    @BeforeEach
    void setUp() {
        batchExecutorFactory = size -> Executors.newFixedThreadPool(size);
        handler = new MultiUploadHandler(detailRepository, fieldMappingBuilder,
                uploadSupport, transferSupport, ftpPool,
                batchExecutorFactory, converterFactory);

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
        void setupBatchModeChain() throws Exception {
            command.setCommandType(CommandType.BATCH);

            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, null)).thenReturn(true);
        }

        @Test
        @DisplayName("should handle batch with details")
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
            when(transferSupport.buildContext(any(), any(), any())).thenReturn(Map.of());
            when(transferSupport.resolveFilePath(anyString(), any(Map.class))).thenReturn(fileInfo);

            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(uploadSupport.preAudit(any(), any(), anyString(), any())).thenReturn(100);

            when(ftpClient.getInputStream(anyString())).thenReturn(mock(InputStream.class));
            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForUpload(any(), any())).thenReturn(mapping);
            when(fileConverter.parse(any(InputStream.class), any())).thenReturn(Collections.emptyIterator());

            when(uploadSupport.insertBatchInTx(any(), any(), any(), anyBoolean())).thenReturn(100);

            handler.handle(command, config, result);

            assertNotNull(result.getResult());
            verify(detailRepository, atLeastOnce()).findUploadDetails(1L, ColumnNames.STATUS_EMPTY);
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

            when(converterFactory.get("CSV")).thenReturn(fileConverter);

            handler.handle(command, config, result);

            verify(detailRepository).updateStatus(200L, ColumnNames.STATUS_SKIPPED, null);
        }

        @Test
        @DisplayName("should mark error on pre-audit failure in batch")
        void shouldMarkErrorOnPreAuditFailure() throws Exception {
            Map<String, Object> detail = new HashMap<>();
            detail.put(ColumnNames.DETAIL_ID, 300L);
            detail.put(ColumnNames.FILE_NAME, "badfile.csv");
            detail.put(ColumnNames.FIELD_NAME, "REGION");
            detail.put(ColumnNames.FIELD_VALUE, "EAST");

            when(detailRepository.findUploadDetails(1L, ColumnNames.STATUS_EMPTY))
                    .thenReturn(Collections.singletonList(detail));

            ResolvedPath fileInfo = ResolvedPath.of("/data/files/badfile.csv");
            when(transferSupport.buildContext(any(), any(), any())).thenReturn(Map.of());
            when(transferSupport.resolveFilePath(anyString(), any(Map.class))).thenReturn(fileInfo);

            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(uploadSupport.preAudit(any(), any(), anyString(), any())).thenReturn(-1);

            handler.handle(command, config, result);

            verify(detailRepository).updateStatus(300L, ColumnNames.STATUS_ERROR, null);
        }

        @Test
        @DisplayName("should pre-check failure return skipped")
        void shouldReturnSkippedOnPreCheckFailure() throws Exception {
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, null)).thenReturn(false);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
            verify(detailRepository, never()).findUploadDetails(anyLong(), anyString());
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

            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, dirInfo)).thenReturn(true);
            when(ftpClient.listFiles(anyString())).thenReturn(new String[]{"/data/files/file1.csv"});

            when(converterFactory.get("CSV")).thenReturn(fileConverter);
        }

        @Test
        @DisplayName("should handle serial mode with directory listing")
        void shouldHandleSerialMode() throws Exception {
            when(ftpClient.listFiles(anyString())).thenReturn(new String[]{"/data/files/file1.csv"});
            when(ftpPool.getClient("ftp1")).thenReturn(ftpClient);
            when(transferSupport.preCheck(any(), any(), any())).thenReturn(true);
            when(uploadSupport.preAudit(any(), any(), anyString(), any())).thenReturn(100);
            when(ftpClient.getInputStream(anyString())).thenReturn(mock(InputStream.class));

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForUpload(eq(config), isNull())).thenReturn(mapping);
            when(fileConverter.parse(any(InputStream.class), any())).thenReturn(Collections.emptyIterator());
            when(uploadSupport.insertBatchInTx(any(), any(), any())).thenReturn(100);

            handler.handle(command, config, result);

            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("should return skipped when no files listed")
        void shouldReturnSkippedWhenNoFiles() throws Exception {
            when(ftpClient.listFiles(anyString())).thenReturn(new String[0]);

            handler.handle(command, config, result);

            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("should return skipped when preCheck fails in listFiles")
        void shouldReturnSkippedWhenPreCheckFailsInListFiles() throws Exception {
            when(transferSupport.preCheck(eq(ftpClient), eq(config), any())).thenReturn(false);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
        }
    }
}
