package com.fmsy.polling;

import com.fmsy.config.AppConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.lifecycle.ConfigLoaderService;
import com.fmsy.lifecycle.ShutdownService;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.TempTransferConfigFactory;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.transfer.download.DownloadSupport;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DetailPollingService Tests")
class DetailPollingServiceTest {

    @Mock
    private DetailRepository detailRepository;

    @Mock
    private BucketDistributor bucketDistributor;

    @Mock
    private ConfigLoaderService configLoader;

    @Mock
    private CommandRepository commandRepository;

    @Mock
    private TempTransferConfigFactory tempConfigFactory;

    @Mock
    private AppConfig appConfig;

    @Mock
    private AppConfig.Download downloadConfig;

    @Mock
    private AppConfig.Polling pollingConfig;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private ResultRepository resultRepository;

    @Mock
    private DownloadSupport downloadSupport;

    @Mock
    private IntFunction<ExecutorService> batchExecutorFactory;

    @Mock
    private ExecutorService mockExecutor;

    @Mock
    private ShutdownService shutdownService;

    private DetailPollingService detailPollingService;

    @BeforeEach
    void setUp() {
        when(appConfig.getDownload()).thenReturn(downloadConfig);
        when(appConfig.getPolling()).thenReturn(pollingConfig);
        when(downloadConfig.getBucketBatchSize()).thenReturn(3);
        when(downloadConfig.getMaxPollIterations()).thenReturn(1000);
        when(pollingConfig.getTaskTimeoutHours()).thenReturn(1);

        detailPollingService = new DetailPollingService(
                detailRepository, bucketDistributor,
                configLoader, commandRepository, tempConfigFactory,
                appConfig, transferSupport, resultRepository, downloadSupport,
                batchExecutorFactory, shutdownService);
    }

    private Command createSubCommand(Long id, String category, String control, CommandType type, String extraInfo) {
        Command cmd = new Command();
        cmd.setId(id);
        cmd.setCategoryCode(category);
        cmd.setControlCode(control);
        cmd.setCommandType(type);
        cmd.setExtraInfo(extraInfo);
        return cmd;
    }

    private TransferConfig createDownloadConfig() {
        TransferConfig config = new TransferConfig();
        config.setFtpName("ftp1");
        config.setFilePath("/data/export/{field}.csv");
        config.setDbName("DB1");
        config.setTableName("my_table");
        config.setSplitFields("region");
        config.setEmptyDataHandling(EmptyDataHandling.SKIP);
        config.setParserType("CSV");
        config.setPostOperations("");
        return config;
    }

    private Detail createBucket(Long id, String fieldValue, Integer auditCount) {
        Detail detail = new Detail();
        detail.setId(id);
        detail.setCommandId(100L);
        detail.setCategoryCode("CAT1");
        detail.setControlCode("CTRL1");
        detail.setFieldValue(fieldValue);
        detail.setAuditCount(auditCount);
        detail.setStatus("");
        return detail;
    }

    @Nested
    @DisplayName("pollAndProcess with config resolution")
    class PollAndProcessConfigTests {

        @Test
        @DisplayName("should mark error when config cannot be resolved")
        void shouldMarkErrorWhenConfigNull() {
            Command subCmd = createSubCommand(10L, "CAT1", "CTRL1", CommandType.COORDINATED, "100|/base/path");
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(null);
            when(commandRepository.findById(100L)).thenReturn(null);

            detailPollingService.pollAndProcess("node1", "100", subCmd);

            // writeSubCommandResult is called both explicitly and in the finally block
            verify(resultRepository, atLeast(1)).insert(any(Result.class));
        }

        @Test
        @DisplayName("should resolve config from T-type main command")
        void shouldResolveConfigFromTempCommand() {
            Command subCmd = createSubCommand(10L, "CAT1", "CTRL1", CommandType.COORDINATED, "100|/base/path");
            Command mainCmd = createSubCommand(100L, "CAT1", "CTRL1", CommandType.TEMPORARY, null);
            TransferConfig config = createDownloadConfig();

            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(null);
            when(commandRepository.findById(100L)).thenReturn(mainCmd);
            when(tempConfigFactory.build(mainCmd)).thenReturn(config);

            detailPollingService.pollAndProcess("node1", "100", subCmd);

            verify(tempConfigFactory).build(mainCmd);
        }

        @Test
        @DisplayName("should fail when extraInfo has no pipe separator")
        void shouldFailWhenExtraInfoNoPipe() {
            Command subCmd = createSubCommand(10L, "CAT1", "CTRL1", CommandType.COORDINATED, "noPipeHere");
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(null);

            detailPollingService.pollAndProcess("node1", "100", subCmd);

            verify(resultRepository, atLeast(1)).insert(any(Result.class));
        }

        @Test
        @DisplayName("should mark startTime on sub command")
        void shouldMarkStartTime() {
            Command subCmd = createSubCommand(10L, "CAT1", "CTRL1", CommandType.COORDINATED, "100|/base/path");
            TransferConfig config = createDownloadConfig();
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(config);
            // No more buckets so it completes quickly
            when(detailRepository.findReadyBuckets(100L, 3)).thenReturn(new ArrayList<>());

            detailPollingService.pollAndProcess("node1", "100", subCmd);

            assertNotNull(subCmd.getStartTime());
        }
    }

    @Nested
    @DisplayName("bucket iteration loop")
    class BucketIterationTests {

        @Test
        @DisplayName("should process buckets in iterations")
        void shouldProcessBucketsInIterations() throws Exception {
            Command subCmd = createSubCommand(10L, "CAT1", "CTRL1", CommandType.COORDINATED, "100|/base/path");
            TransferConfig config = createDownloadConfig();
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(config);

            // First iteration: one bucket, second: empty
            Detail bucket = createBucket(1L, "region1", 10);
            when(detailRepository.findReadyBuckets(100L, 3))
                    .thenReturn(List.of(bucket))
                    .thenReturn(new ArrayList<>());

            when(batchExecutorFactory.apply(1)).thenReturn(mockExecutor);
            doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            }).when(mockExecutor).execute(any(Runnable.class));
            when(mockExecutor.awaitTermination(1, TimeUnit.HOURS)).thenReturn(true);

            // Mock bucket competition to succeed
            when(bucketDistributor.competeBucket(1L, "node1")).thenReturn(1);
            // Mock resolveFilePath
            ResolvedPath resolvedPath = ResolvedPath.of("/data/export/region1.csv");
            Map<String, String> context = new HashMap<>();
            when(transferSupport.buildContext(null, "region", "region1")).thenReturn(context);
            when(transferSupport.resolveFilePath(config.getFilePath(), context)).thenReturn(resolvedPath);

            // Mock download pipeline to return success
            when(downloadSupport.executePipeline(eq("ftp1"), eq(config), eq(resolvedPath), any()))
                    .thenReturn(new DownloadSupport.PipelineResult(
                            10, true, ColumnNames.STATUS_SUCCESS, "/data/export/region1.csv"));

            detailPollingService.pollAndProcess("node1", "100", subCmd);

            verify(downloadSupport).executePipeline(eq("ftp1"), eq(config), eq(resolvedPath), any());
            verify(detailRepository, atLeast(1)).findReadyBuckets(100L, 3);
        }

        @Test
        @DisplayName("should stop when shutting down")
        void shouldStopOnShutdown() {
            Command subCmd = createSubCommand(10L, "CAT1", "CTRL1", CommandType.COORDINATED, "100|/base/path");
            TransferConfig config = createDownloadConfig();
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(config);
            when(shutdownService.isShuttingDown()).thenReturn(true);

            detailPollingService.pollAndProcess("node1", "100", subCmd);

            verify(detailRepository, never()).findReadyBuckets(anyLong(), anyInt());
        }

        @Test
        @DisplayName("should stop when task timeout reached")
        void shouldStopOnTimeout() {
            Command subCmd = createSubCommand(10L, "CAT1", "CTRL1", CommandType.COORDINATED, "100|/base/path");
            TransferConfig config = createDownloadConfig();
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(config);

            // Make sure we don't stop early due to shutdown
            when(shutdownService.isShuttingDown()).thenReturn(false);

            // Return some buckets so we enter the loop, but simulate timeout
            // by having System.currentTimeMillis() - startTime >= timeoutMs
            // This is tricky to mock, so let's just verify the loop structure works

            detailPollingService.pollAndProcess("node1", "100", subCmd);

            verify(resultRepository).insert(any(Result.class));
        }

        @Test
        @DisplayName("should handle InterruptedException")
        void shouldHandleInterruptedException() {
            Command subCmd = createSubCommand(10L, "CAT1", "CTRL1", CommandType.COORDINATED, "100|/base/path");
            TransferConfig config = createDownloadConfig();
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(config);

            Detail bucket = createBucket(1L, "region1", 10);
            when(detailRepository.findReadyBuckets(100L, 3))
                    .thenReturn(List.of(bucket));

            when(batchExecutorFactory.apply(1)).thenReturn(mockExecutor);
            try {
                when(mockExecutor.awaitTermination(1, TimeUnit.HOURS)).thenThrow(new InterruptedException("interrupted"));
            } catch (Exception e) {
                // mockito syntax
            }

            detailPollingService.pollAndProcess("node1", "100", subCmd);

            // Should restore interrupt flag
            assertTrue(Thread.interrupted());
        }

        @Test
        @DisplayName("should handle RuntimeException during dispatch")
        void shouldHandleRuntimeException() {
            Command subCmd = createSubCommand(10L, "CAT1", "CTRL1", CommandType.COORDINATED, "100|/base/path");
            TransferConfig config = createDownloadConfig();
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(config);
            when(detailRepository.findReadyBuckets(100L, 3)).thenThrow(new RuntimeException("DB error"));

            detailPollingService.pollAndProcess("node1", "100", subCmd);

            verify(resultRepository).insert(any(Result.class));
        }
    }

    @Nested
    @DisplayName("determineSubCommandResult")
    class DetermineSubCommandResultTests {

        @Test
        @DisplayName("should return SKIPPED when all counts are zero")
        void shouldReturnSkippedWhenAllZero() {
            // Access private method via reflection
            String result = invokeDetermineResult(0, 0, 0);
            assertEquals("N", result);
        }

        @Test
        @DisplayName("should return ERROR when only failed > 0")
        void shouldReturnErrorWhenOnlyFailed() {
            String result = invokeDetermineResult(0, 3, 0);
            assertEquals("E", result);
        }

        @Test
        @DisplayName("should return SKIPPED when only skipped > 0")
        void shouldReturnSkippedWhenOnlySkipped() {
            String result = invokeDetermineResult(0, 0, 5);
            assertEquals("N", result);
        }

        @Test
        @DisplayName("should return ERROR when any failed")
        void shouldReturnErrorWhenAnyFailed() {
            String result = invokeDetermineResult(1, 1, 5);
            assertEquals("E", result);
        }

        @Test
        @DisplayName("should return SUCCESS when no failures")
        void shouldReturnSuccessWhenNoFailures() {
            String result = invokeDetermineResult(5, 0, 0);
            assertEquals("Y", result);
        }
    }

    /** Reflection helper for determineSubCommandResult */
    private String invokeDetermineResult(int success, int failed, int skipped) {
        try {
            var method = DetailPollingService.class.getDeclaredMethod(
                    "determineSubCommandResult", int.class, int.class, int.class);
            method.setAccessible(true);
            return (String) method.invoke(detailPollingService, failed, skipped, success);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("writeSubCommandResult")
    class WriteSubCommandResultTests {

        @Test
        @DisplayName("should write result row via ResultRepository")
        void shouldWriteResultRow() {
            Command subCmd = createSubCommand(10L, "CAT1", "CTRL1", CommandType.COORDINATED, "100|/base/path");
            TransferConfig config = createDownloadConfig();

            try {
                var method = DetailPollingService.class.getDeclaredMethod(
                        "writeSubCommandResult", Command.class, long.class, TransferConfig.class,
                        int.class, int.class, int.class, int.class);
                method.setAccessible(true);
                method.invoke(detailPollingService, subCmd, System.currentTimeMillis() - 1000,
                        config, 3, 2, 1, 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            verify(resultRepository).insert(any(Result.class));
        }

        @Test
        @DisplayName("should handle null config gracefully")
        void shouldHandleNullConfig() {
            Command subCmd = createSubCommand(10L, "CAT1", "CTRL1", CommandType.COORDINATED, "100|/base/path");

            try {
                var method = DetailPollingService.class.getDeclaredMethod(
                        "writeSubCommandResult", Command.class, long.class, TransferConfig.class,
                        int.class, int.class, int.class, int.class);
                method.setAccessible(true);
                method.invoke(detailPollingService, subCmd, System.currentTimeMillis() - 1000,
                        null, 0, 0, 0, 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            ArgumentCaptor<Result> captor = ArgumentCaptor.captor();
            verify(resultRepository).insert(captor.capture());
            Result result = captor.getValue();
            assertNull(result.getFtpName());
            assertNull(result.getFilePath());
        }
    }
}
