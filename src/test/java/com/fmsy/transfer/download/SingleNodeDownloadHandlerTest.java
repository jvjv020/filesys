package com.fmsy.transfer.download;

import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.CommandType;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.BucketDistributor;
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

import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SingleNodeDownloadHandler Tests")
class SingleNodeDownloadHandlerTest {

    @Mock
    private FtpPool ftpPool;

    @Mock
    private BucketDistributor bucketDistributor;

    @Mock
    private TargetTableRepository targetTableRepository;

    @Mock
    private FieldMappingBuilder fieldMappingBuilder;

    @Mock
    private DownloadSupport downloadSupport;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private ConverterFactory converterFactory;

    @Mock
    private FtpClient ftpClient;

    @Mock
    private FileConverter fileConverter;

    private IntFunction<ExecutorService> batchExecutorFactory;
    private SingleNodeDownloadHandler handler;

    private Command command;
    private TransferConfig config;
    private Result result;
    private ResolvedPath baseFileInfo;

    @BeforeEach
    void setUp() {
        batchExecutorFactory = size -> Executors.newFixedThreadPool(size);
        handler = new SingleNodeDownloadHandler(ftpPool, bucketDistributor,
                targetTableRepository, fieldMappingBuilder,
                batchExecutorFactory, downloadSupport, transferSupport, converterFactory);

        command = new Command();
        command.setId(1L);
        command.setCategoryCode("CAT001");
        command.setControlCode("CTRL001");
        command.setCommandType(CommandType.SERIAL);

        config = new TransferConfig();
        config.setFtpName("ftp1");
        config.setFilePath("/data/output_{REGION}.csv");
        config.setParserType("CSV");
        config.setTableName("mytable");
        config.setDbName("DB1");
        config.setSplitFields("REGION");
        config.setPostOperations("SUB:{X}.flg;L S M");
        config.setNodeId("node-1");

        result = new Result();
        baseFileInfo = ResolvedPath.of("/data/output_{REGION}.csv");
    }

    @Nested
    @DisplayName("handle - no split fields")
    class HandleNoSplitFields {

        @Test
        @DisplayName("should return early when split fields is empty")
        void shouldReturnEarlyWhenSplitFieldsEmpty() throws Exception {
            config.setSplitFields(null);

            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(baseFileInfo);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        }
    }

    @Nested
    @DisplayName("handle - pre-check failure")
    class HandlePreCheckFailure {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(baseFileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, baseFileInfo)).thenReturn(false);
        }

        @Test
        @DisplayName("should set outcome when pre-check fails")
        void shouldSetOutcomeWhenPreCheckFails() throws Exception {
            handler.handle(command, config, result);

            assertNotNull(result.getResult());
        }
    }

    @Nested
    @DisplayName("handle - SERIAL mode buckets")
    class HandleSerialBuckets {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(baseFileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, baseFileInfo)).thenReturn(true);
            when(bucketDistributor.distinctBuckets(config)).thenReturn(List.of("EAST", "WEST"));

            // Bucket processing
            when(ftpPool.getClient("ftp1")).thenReturn(ftpClient);
            when(transferSupport.buildContext(isNull(), eq("REGION"), anyString()))
                    .thenReturn(Map.of());
            when(transferSupport.resolveFilePath(anyString(), anyMap()))
                    .thenReturn(ResolvedPath.of("/data/output_EAST.csv"));

            when(targetTableRepository.countByBucket(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(50);
            when(transferSupport.handleEmptyData(anyInt(), any())).thenReturn(true);

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));
            when(fieldMappingBuilder.buildForDownload(config)).thenReturn(mapping);
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(targetTableRepository.streamBucketData(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(mock(com.fmsy.repository.TargetTableRepository.DataStream.class));
            when(ftpClient.getOutputStream(anyString())).thenReturn(mock(OutputStream.class));
        }

        @Test
        @DisplayName("should process SERIAL mode buckets")
        void shouldProcessSerialBuckets() throws Exception {
            handler.handle(command, config, result);

            verify(ftpClient, atLeastOnce()).completePendingCommand();
            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("should generate total flag when postOps contains TOTAL")
        void shouldGenerateTotalFlag() throws Exception {
            config.setPostOperations("TOTAL:/total.flg;L S M;SUB:{X}.flg;L S M");

            handler.handle(command, config, result);

            assertNotNull(result.getResult());
        }
    }

    @Nested
    @DisplayName("handle - BATCH mode buckets")
    class HandleBatchBuckets {

        @BeforeEach
        void setup() throws Exception {
            command.setCommandType(CommandType.BATCH);

            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(baseFileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, baseFileInfo)).thenReturn(true);

            Detail bucket1 = new Detail();
            bucket1.setId(10L);
            bucket1.setFieldValue("EAST");
            Detail bucket2 = new Detail();
            bucket2.setId(11L);
            bucket2.setFieldValue("WEST");
            when(bucketDistributor.getBuckets(1L, Integer.MAX_VALUE))
                    .thenReturn(List.of(bucket1, bucket2));

            when(ftpPool.getClient("ftp1")).thenReturn(ftpClient);
            when(transferSupport.buildContext(isNull(), eq("REGION"), anyString()))
                    .thenReturn(Map.of());
            when(transferSupport.resolveFilePath(anyString(), anyMap()))
                    .thenReturn(ResolvedPath.of("/data/output_EAST.csv"));

            when(targetTableRepository.countByBucket(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(50);
            when(transferSupport.handleEmptyData(anyInt(), any())).thenReturn(true);

            FieldMapping mapping = new FieldMapping();
            when(fieldMappingBuilder.buildForDownload(config)).thenReturn(mapping);
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(targetTableRepository.streamBucketData(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(mock(com.fmsy.repository.TargetTableRepository.DataStream.class));
            when(ftpClient.getOutputStream(anyString())).thenReturn(mock(OutputStream.class));
        }

        @Test
        @DisplayName("should process BATCH mode buckets")
        void shouldProcessBatchBuckets() throws Exception {
            handler.handle(command, config, result);

            verify(bucketDistributor).getBuckets(1L, Integer.MAX_VALUE);
            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("should handle no buckets case")
        void shouldHandleNoBuckets() throws Exception {
            when(bucketDistributor.getBuckets(1L, Integer.MAX_VALUE)).thenReturn(List.of());

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        }
    }
}
