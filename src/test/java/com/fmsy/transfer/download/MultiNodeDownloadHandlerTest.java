package com.fmsy.transfer.download;

import com.fmsy.enums.CommandType;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.ftp.FtpClient;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MultiNodeDownloadHandler Tests")
class MultiNodeDownloadHandlerTest {

    @Mock
    private BucketDistributor bucketDistributor;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private FtpClient ftpClient;

    @Mock
    private SplitFlowService splitFlowService;

    @Mock
    private MergeFlowService mergeFlowService;

    @Mock
    private CommandRepository commandRepository;

    @InjectMocks
    private MultiNodeDownloadHandler handler;

    private Command command;
    private TransferConfig config;
    private Result result;
    private ResolvedPath baseFileInfo;

    @BeforeEach
    void setUp() {
        command = new Command();
        command.setId(1L);
        command.setCategoryCode("CAT001");
        command.setControlCode("CTRL001");
        command.setCommandType(CommandType.SERIAL);

        config = new TransferConfig();
        config.setFtpName("ftp1");
        config.setFilePath("/data/output_{REGION}.csv");
        config.setSplitFields("REGION");
        config.setTableName("mytable");
        config.setDbName("DB1");
        config.setCategoryCode("CAT001");
        config.setControlCode("CTRL001");

        result = new Result();
        baseFileInfo = ResolvedPath.of("/data/output_{REGION}.csv");
    }

    @Nested
    @DisplayName("handle - pre-check and validation")
    class HandlePreCheckAndValidation {

        @Test
        @DisplayName("should mark children failed when split fields is null")
        void shouldMarkChildrenFailedWhenSplitFieldsNull() throws Exception {
            config.setSplitFields(null);
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(baseFileInfo);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            assertTrue(result.getDescription().contains("No split fields"));
        }

        @Test
        @DisplayName("should mark children failed when pre-check fails")
        void shouldMarkChildrenFailedWhenPreCheckFails() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(baseFileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, baseFileInfo)).thenReturn(false);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }

        @Test
        @DisplayName("should mark children failed when split fields is empty")
        void shouldMarkChildrenFailedWhenSplitFieldsEmpty() throws Exception {
            config.setSplitFields("");
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(baseFileInfo);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }
    }

    @Nested
    @DisplayName("handle - SERIAL mode")
    class HandleSerialMode {

        @BeforeEach
        void setup() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(baseFileInfo);
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(transferSupport.preCheck(ftpClient, config, baseFileInfo)).thenReturn(true);
        }

        @Test
        @DisplayName("should call startSplitAsync for SERIAL mode")
        void shouldUseStartSplitAsync() throws Exception {
            handler.handle(command, config, result);

            // SERIAL 模式改为异步切分,handle 中立即调用 markChildrenCreated 标记为 P
            // 再调用 startSplitAsync 异步执行
            verify(splitFlowService).startSplitAsync(eq(command.getId()), eq(config),
                    any(Runnable.class), any());
            assertEquals(ColumnNames.STATUS_PROCESSING, result.getResult());
        }

        @Test
        @DisplayName("should not call splitSync for SERIAL mode")
        void shouldNotCallSplitSync() throws Exception {
            handler.handle(command, config, result);

            // 异步切分后不再调用同步 splitSync
            verify(splitFlowService, never()).splitSync(any(), any());
        }

        @Test
        @DisplayName("async callback should create child commands and start merge when split succeeds")
        void asyncCallbackShouldCreateChildrenAndMerge() throws Exception {
            // 捕获 startSplitAsync 的 onComplete 回调
            final Runnable[] onComplete = new Runnable[1];
            doAnswer(invocation -> {
                onComplete[0] = invocation.getArgument(2);
                return null;
            }).when(splitFlowService).startSplitAsync(eq(command.getId()), eq(config),
                    any(Runnable.class), any());

            handler.handle(command, config, result);

            // 模拟切分完成回调
            assertNotNull(onComplete[0]);
            when(bucketDistributor.createChildCommands(command.getId(), "CAT001", "CTRL001",
                    baseFileInfo.fullPath())).thenReturn(3);
            onComplete[0].run();

            verify(bucketDistributor).createChildCommands(command.getId(), "CAT001", "CTRL001",
                    baseFileInfo.fullPath());
            verify(mergeFlowService).startMergeAsync(eq(1L), eq(config), eq(baseFileInfo),
                    any(Runnable.class), any(Runnable.class));
        }

        @Test
        @DisplayName("async callback should skip merge when no buckets created")
        void asyncCallbackShouldSkipMergeWhenNoBuckets() throws Exception {
            final Runnable[] onComplete = new Runnable[1];
            doAnswer(invocation -> {
                onComplete[0] = invocation.getArgument(2);
                return null;
            }).when(splitFlowService).startSplitAsync(eq(command.getId()), eq(config),
                    any(Runnable.class), any());

            handler.handle(command, config, result);

            // 模拟切分完成但无桶
            assertNotNull(onComplete[0]);
            when(bucketDistributor.createChildCommands(command.getId(), "CAT001", "CTRL001",
                    baseFileInfo.fullPath())).thenReturn(0);
            onComplete[0].run();

            verify(bucketDistributor).createChildCommands(command.getId(), "CAT001", "CTRL001",
                    baseFileInfo.fullPath());
            verify(mergeFlowService, never()).startMergeAsync(any(), any(), any(), any(), any());
            verify(commandRepository).updateStatus(command.getId(), ColumnNames.STATUS_SKIPPED);
        }

        @Test
        @DisplayName("async callback should mark main command error when split fails")
        void asyncCallbackShouldMarkErrorWhenSplitFails() throws Exception {
            final java.util.function.Consumer<Exception>[] onError = new java.util.function.Consumer[1];
            doAnswer(invocation -> {
                onError[0] = invocation.getArgument(3);
                return null;
            }).when(splitFlowService).startSplitAsync(eq(command.getId()), eq(config),
                    any(Runnable.class), any());

            handler.handle(command, config, result);

            // 模拟切分失败
            assertNotNull(onError[0]);
            onError[0].accept(new RuntimeException("Split failed"));

            verify(commandRepository).updateStatus(command.getId(), ColumnNames.STATUS_ERROR);
        }

        @Test
        @DisplayName("should mark children created immediately for async SERIAL mode")
        void shouldMarkChildrenCreatedImmediately() throws Exception {
            handler.handle(command, config, result);

            // 异步模式下立即标记为 P,不等切分完成
            assertEquals(ColumnNames.STATUS_PROCESSING, result.getResult());
        }
    }

    @Nested
    @DisplayName("handle - BATCH mode")
    class HandleBatchMode {

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
        }

        @Test
        @DisplayName("should call prepareBatchChildren for BATCH mode")
        void shouldCallPrepareBatchChildren() throws Exception {
            when(bucketDistributor.prepareBatchChildren(command, config, baseFileInfo.fullPath(), "REGION"))
                    .thenReturn(2);

            handler.handle(command, config, result);

            verify(bucketDistributor).prepareBatchChildren(command, config,
                    baseFileInfo.fullPath(), "REGION");
            verify(mergeFlowService).startMergeAsync(eq(1L), eq(config), eq(baseFileInfo),
                    any(Runnable.class), any(Runnable.class));
            assertEquals(ColumnNames.STATUS_PROCESSING, result.getResult());
        }

        @Test
        @DisplayName("should mark children failed when prepareBatchChildren returns 0")
        void shouldMarkChildrenFailedWhenZeroChildren() throws Exception {
            when(bucketDistributor.prepareBatchChildren(command, config, baseFileInfo.fullPath(), "REGION"))
                    .thenReturn(0);

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            verify(mergeFlowService, never()).startMergeAsync(any(), any(), any(), any(), any());
        }
    }
}
