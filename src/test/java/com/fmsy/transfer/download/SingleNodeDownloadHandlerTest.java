package com.fmsy.transfer.download;

import com.fmsy.enums.CommandType;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.transfer.download.BucketProcessor.BucketBatchResult;
import com.fmsy.transfer.download.BucketProcessor.BucketProcessingOptions;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SingleNodeDownloadHandler Tests")
class SingleNodeDownloadHandlerTest {

    @Mock
    private BucketProcessor bucketProcessor;

    @Mock
    private BucketDistributor bucketDistributor;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private DownloadSupport downloadSupport;

    private SingleNodeDownloadHandler handler;

    private Command command;
    private TransferConfig config;
    private Result result;
    private ResolvedPath baseFileInfo;

    @BeforeEach
    void setUp() {
        handler = new SingleNodeDownloadHandler(bucketProcessor, bucketDistributor,
                transferSupport, downloadSupport);

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
        when(transferSupport.preCheck(any(), eq(config), eq(baseFileInfo))).thenReturn(true);
    }

    /** 创建一个 BucketBatchResult 模拟, 含指定数量的成功/失败/跳过桶 */
    private BucketBatchResult createMockResult(int success, int failed, int skipped, int records) {
        var mockResult = mock(BucketBatchResult.class);
        when(mockResult.getSuccessCount()).thenReturn(success);
        when(mockResult.getFailedCount()).thenReturn(failed);
        when(mockResult.getSkippedCount()).thenReturn(skipped);
        when(mockResult.getTotalRecordCount()).thenReturn(records);
        when(mockResult.isAllSuccess()).thenReturn(failed == 0);
        when(mockResult.getGeneratedFiles()).thenReturn(List.of());
        when(mockResult.determineStatus()).thenReturn(
                failed > 0 ? ColumnNames.STATUS_ERROR
                        : skipped > 0 ? ColumnNames.STATUS_SKIPPED
                        : ColumnNames.STATUS_SUCCESS);
        return mockResult;
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
            when(transferSupport.preCheck(any(), eq(config), eq(baseFileInfo))).thenReturn(false);

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

            // BucketProcessor 返回成功结果(2个桶各50条)
            BucketBatchResult mockResult = createMockResult(2, 0, 0, 100);
            when(bucketProcessor.processAll(anyList(), eq(config), eq(baseFileInfo),
                    eq("ftp1"), any(BucketProcessingOptions.class), eq("node-1")))
                    .thenReturn(mockResult);
        }

        @Test
        @DisplayName("should process SERIAL mode buckets via BucketProcessor")
        void shouldProcessSerialBuckets() throws Exception {
            handler.handle(command, config, result);

            verify(bucketProcessor).processAll(anyList(), eq(config), eq(baseFileInfo),
                    eq("ftp1"), any(BucketProcessingOptions.class), eq("node-1"));
            assertNotNull(result.getResult());
        }

        @Test
        @DisplayName("should generate total flag when postOps contains TOTAL")
        void shouldGenerateTotalFlag() throws Exception {
            config.setPostOperations("TOTAL:/total.flg;L S M,SUB:{X}.flg;L S M");

            handler.handle(command, config, result);

            // TOTAL flag generation is invoked via executeWithClient
            // The mock preCheck returns true, so handle should succeed
            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        }

        @Test
        @DisplayName("should count pipeline failures as errors")
        void shouldCountPipelineFailures() throws Exception {
            BucketBatchResult mockResult = createMockResult(1, 1, 0, 50);
            when(bucketProcessor.processAll(anyList(), eq(config), eq(baseFileInfo),
                    eq("ftp1"), any(BucketProcessingOptions.class), eq("node-1")))
                    .thenReturn(mockResult);

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

            Detail bucket1 = new Detail();
            bucket1.setId(10L);
            bucket1.setFieldValue("EAST");
            Detail bucket2 = new Detail();
            bucket2.setId(11L);
            bucket2.setFieldValue("WEST");
            when(bucketDistributor.getBuckets(1L, Integer.MAX_VALUE))
                    .thenReturn(List.of(bucket1, bucket2));

            BucketBatchResult mockResult = createMockResult(2, 0, 0, 100);
            when(bucketProcessor.processAll(anyList(), eq(config), eq(baseFileInfo),
                    eq("ftp1"), any(BucketProcessingOptions.class), eq("node-1")))
                    .thenReturn(mockResult);
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
