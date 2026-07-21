package com.fmsy.transfer.download;

import com.fmsy.enums.CommandType;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.download.BucketDistributor;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
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
@DisplayName("SingleNodeDownloadHandler Tests")
class SingleNodeDownloadHandlerTest {

    @Mock
    private BucketDistributor bucketDistributor;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private DownloadSupport downloadSupport;

    @Mock
    private FieldMappingBuilder fieldMappingBuilder;

    private SingleNodeDownloadHandler handler;

    private Command command;
    private TransferConfig config;
    private Result result;
    private ResolvedPath baseFileInfo;

    @BeforeEach
    void setUp() {
        IntFunction<ExecutorService> factory = n -> Executors.newFixedThreadPool(n);
        handler = new SingleNodeDownloadHandler(bucketDistributor,
                transferSupport, downloadSupport, fieldMappingBuilder, factory);

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

    private void mockPreCheckSuccess() throws Exception {
        when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(baseFileInfo);
        when(transferSupport.executeWithClient(eq("ftp1"), any()))
                .thenAnswer(invocation -> {
                    TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                    return cb.run(mock(com.fmsy.ftp.FtpClient.class));
                });
        when(downloadSupport.preCheckAndMkdirs(any(), eq(config), eq(baseFileInfo),
                eq(baseFileInfo.fullPath()))).thenReturn(true);
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

        @Test
        @DisplayName("should set outcome when pre-check fails")
        void shouldSetOutcomeWhenPreCheckFails() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(baseFileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(mock(com.fmsy.ftp.FtpClient.class));
                    });
            when(downloadSupport.preCheckAndMkdirs(any(), eq(config), eq(baseFileInfo),
                    eq(baseFileInfo.fullPath()))).thenReturn(false);

            handler.handle(command, config, result);

            assertNotNull(result.getResult());
        }
    }

    @Nested
    @DisplayName("handle - SERIAL mode buckets")
    class HandleSerialBuckets {

        @BeforeEach
        void setup() throws Exception {
            mockPreCheckSuccess();
            when(bucketDistributor.distinctBuckets(config)).thenReturn(List.of("EAST", "WEST"));
            when(fieldMappingBuilder.buildForDownload(config)).thenReturn(null);

            // 每个桶的管线返回成功结果(50条)
            when(downloadSupport.executeBucketPipeline(eq("ftp1"), eq(config), any(), any(),
                    any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new DownloadSupport.PipelineResult(50, true, ColumnNames.STATUS_SUCCESS, ""));
        }

        @Test
        @DisplayName("should process SERIAL mode buckets")
        void shouldProcessSerialBuckets() throws Exception {
            handler.handle(command, config, result);

            // 验证每个桶都调用了 executeBucketPipeline
            verify(downloadSupport, times(2)).executeBucketPipeline(
                    eq("ftp1"), eq(config), any(), any(),
                    any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            assertEquals(100, result.getRecordCount());
        }

        @Test
        @DisplayName("should generate total flag when postOps contains TOTAL")
        void shouldGenerateTotalFlag() throws Exception {
            config.setPostOperations("TOTAL:/total.flg;L S M,SUB:{X}.flg;L S M");

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        }

        @Test
        @DisplayName("should count pipeline failures as errors")
        void shouldCountPipelineFailures() throws Exception {
            // 第一个桶成功(50条),第二个桶失败
            when(downloadSupport.executeBucketPipeline(eq("ftp1"), eq(config), any(), any(),
                    any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(
                            new DownloadSupport.PipelineResult(50, true, ColumnNames.STATUS_SUCCESS, ""),
                            new DownloadSupport.PipelineResult(0, false, ColumnNames.STATUS_ERROR, ""));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }
    }

    @Nested
    @DisplayName("handle - BATCH mode buckets")
    class HandleBatchBuckets {

        @BeforeEach
        void setup() throws Exception {
            command.setCommandType(CommandType.BATCH);
            mockPreCheckSuccess();
            when(fieldMappingBuilder.buildForDownload(config)).thenReturn(null);

            Detail bucket1 = new Detail();
            bucket1.setId(10L);
            bucket1.setFieldValue("EAST");
            Detail bucket2 = new Detail();
            bucket2.setId(11L);
            bucket2.setFieldValue("WEST");
            when(bucketDistributor.getBuckets(1L, Integer.MAX_VALUE))
                    .thenReturn(List.of(bucket1, bucket2));

            // 每个桶的管线返回成功结果(50条)
            when(downloadSupport.executeBucketPipeline(eq("ftp1"), eq(config), any(), any(),
                    any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new DownloadSupport.PipelineResult(50, true, ColumnNames.STATUS_SUCCESS, ""));
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