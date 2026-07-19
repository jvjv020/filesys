package com.fmsy.polling;

import com.fmsy.config.AppConfig;
import com.fmsy.lifecycle.ShutdownService;
import com.fmsy.model.Command;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.transfer.TransferService;
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
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PollingService Tests")
class PollingServiceTest {

    @Mock
    private AppConfig appConfig;

    @Mock
    private AppConfig.Polling pollingConfig;

    @Mock
    private AppConfig.Node nodeConfig;

    @Mock
    private ShutdownService shutdownService;

    @Mock
    private ResultRepository resultRepository;

    @Mock
    private TransferService transferService;

    @Mock
    private CommandRepository commandRepository;

    @Mock
    private DetailRepository detailRepository;

    @Mock
    private BatchDispatcher batchDispatcher;

    private PollingService pollingService;

    @BeforeEach
    void setUp() {
        when(appConfig.getPolling()).thenReturn(pollingConfig);
        when(appConfig.getNode()).thenReturn(nodeConfig);
        when(nodeConfig.getId()).thenReturn("node1");
        when(pollingConfig.getTaskTimeoutHours()).thenReturn(1);
        when(pollingConfig.getBatchSize()).thenReturn(20);
        pollingService = new PollingService(
                appConfig, shutdownService, resultRepository,
                transferService, commandRepository, detailRepository, batchDispatcher);
    }

    @Nested
    @DisplayName("poll() main loop")
    class PollMainLoopTests {

        @Test
        @DisplayName("should skip poll when shutting down")
        void shouldSkipWhenShuttingDown() {
            when(shutdownService.isShuttingDown()).thenReturn(true);

            pollingService.poll();

            verify(commandRepository, never()).findReadyCommands(anyInt());
            verify(batchDispatcher, never()).dispatch(anyList(), anyMap(), any());
        }

        @Test
        @DisplayName("should execute full poll cycle with ready commands")
        void shouldExecuteFullCycle() {
            when(shutdownService.isShuttingDown()).thenReturn(false);
            List<Command> readyCommands = new ArrayList<>();
            Command cmd = new Command();
            cmd.setId(1L);
            cmd.setCategoryCode("CAT1");
            cmd.setControlCode("CTRL1");
            readyCommands.add(cmd);

            when(commandRepository.findReadyCommands(20)).thenReturn(readyCommands);
            when(commandRepository.findProcessingCommands()).thenReturn(new ArrayList<>());

            pollingService.poll();

            verify(commandRepository).releaseTimeoutCommands("node1", 1, "E");
            verify(commandRepository).findReadyCommands(20);
            verify(batchDispatcher).dispatch(eq(readyCommands), anyMap(), any());
        }

        @Test
        @DisplayName("should handle empty ready commands gracefully")
        void shouldHandleEmptyCommands() {
            when(shutdownService.isShuttingDown()).thenReturn(false);
            when(commandRepository.findReadyCommands(20)).thenReturn(new ArrayList<>());
            when(commandRepository.findProcessingCommands()).thenReturn(new ArrayList<>());

            pollingService.poll();

            verify(batchDispatcher).dispatch(anyList(), anyMap(), any());
        }

        @Test
        @DisplayName("should catch DataAccessException without crashing")
        void shouldCatchDataAccessException() {
            when(shutdownService.isShuttingDown()).thenReturn(false);
            when(commandRepository.findReadyCommands(20))
                    .thenThrow(new DataAccessException("DB error") {});

            // Should not throw
            pollingService.poll();

            // Subsequent calls should still work
            verify(commandRepository).findReadyCommands(20);
        }

        @Test
        @DisplayName("should catch generic Exception without crashing")
        void shouldCatchGenericException() {
            when(shutdownService.isShuttingDown()).thenReturn(false);
            when(commandRepository.findReadyCommands(20))
                    .thenThrow(new RuntimeException("Unexpected error"));

            pollingService.poll();

            verify(commandRepository).findReadyCommands(20);
        }

        @Test
        @DisplayName("should propagate processing map to batch dispatcher")
        void shouldPropagateProcessingMap() {
            when(shutdownService.isShuttingDown()).thenReturn(false);
            when(commandRepository.findReadyCommands(20)).thenReturn(new ArrayList<>());

            List<Map<String, Object>> processingRows = new ArrayList<>();
            Map<String, Object> row = new HashMap<>();
            row.put("类别代号", "CAT1");
            row.put("控制代号", "CTRL1");
            row.put("处理节点", "node2");
            row.put("指令类型", null);
            row.put("额外信息", null);
            row.put("自增列", 100L);
            processingRows.add(row);
            when(commandRepository.findProcessingCommands()).thenReturn(processingRows);

            pollingService.poll();

            ArgumentCaptor<Map<String, CommandProcessingTracker>> mapCaptor = ArgumentCaptor.captor();
            verify(batchDispatcher).dispatch(anyList(), mapCaptor.capture(), any());
            Map<String, CommandProcessingTracker> captured = mapCaptor.getValue();
            assertNotNull(captured.get("CAT1_CTRL1"));
        }
    }

    @Nested
    @DisplayName("releaseTimeoutTasks")
    class ReleaseTimeoutTasksTests {

        @Test
        @DisplayName("should release timeout commands and write result rows")
        void shouldReleaseTimeoutCommands() {
            when(shutdownService.isShuttingDown()).thenReturn(false);
            when(commandRepository.findReadyCommands(20)).thenReturn(new ArrayList<>());
            when(commandRepository.findProcessingCommands()).thenReturn(new ArrayList<>());

            List<Map<String, Object>> timeoutJobs = new ArrayList<>();
            Map<String, Object> job = new HashMap<>();
            job.put("自增列", 1L);
            job.put("类别代号", "CAT1");
            job.put("控制代号", "CTRL1");
            timeoutJobs.add(job);
            when(commandRepository.findTimeoutJobs("node1", 1)).thenReturn(timeoutJobs);

            pollingService.poll();

            verify(commandRepository).releaseTimeoutCommands("node1", 1, "E");
            verify(resultRepository).insertSimple(1L, "CAT1", "CTRL1", "E", "执行超时自动释放");
        }

        @Test
        @DisplayName("should handle DataAccessException during timeout release")
        void shouldHandleDataAccessException() {
            when(shutdownService.isShuttingDown()).thenReturn(false);
            when(commandRepository.findReadyCommands(20)).thenReturn(new ArrayList<>());
            when(commandRepository.findProcessingCommands()).thenReturn(new ArrayList<>());
            doThrow(new DataAccessException("DB error") {})
                    .when(commandRepository).releaseTimeoutCommands("node1", 1, "E");

            // Should not throw
            pollingService.poll();
            verify(commandRepository).releaseTimeoutCommands("node1", 1, "E");
        }
    }

    @Nested
    @DisplayName("loadProcessingCommands")
    class LoadProcessingCommandsTests {

        @Test
        @DisplayName("should build processing map from DB rows")
        void shouldBuildProcessingMap() {
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Object> row = new HashMap<>();
            row.put("自增列", 10L);
            row.put("类别代号", "CAT1");
            row.put("控制代号", "CTRL1");
            row.put("处理节点", "node1");
            row.put("指令类型", null);
            row.put("额外信息", null);
            rows.add(row);

            when(commandRepository.findProcessingCommands()).thenReturn(rows);
            when(shutdownService.isShuttingDown()).thenReturn(false);
            when(commandRepository.findReadyCommands(20)).thenReturn(new ArrayList<>());

            pollingService.poll();

            verify(commandRepository).findProcessingCommands();
        }

        @Test
        @DisplayName("should handle S-type commands in processing map")
        void shouldHandleSTypeCommands() {
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Object> row = new HashMap<>();
            row.put("自增列", 20L);
            row.put("类别代号", "CAT1");
            row.put("控制代号", "CTRL1");
            row.put("处理节点", "node1");
            row.put("指令类型", "S");
            row.put("额外信息", "mainCmdId|/base/path");
            rows.add(row);

            when(commandRepository.findProcessingCommands()).thenReturn(rows);
            when(shutdownService.isShuttingDown()).thenReturn(false);
            when(commandRepository.findReadyCommands(20)).thenReturn(new ArrayList<>());

            pollingService.poll();

            verify(commandRepository).findProcessingCommands();
        }

        @Test
        @DisplayName("should handle DataAccessException when loading commands")
        void shouldHandleDataAccessException() {
            when(commandRepository.findProcessingCommands())
                    .thenThrow(new DataAccessException("DB error") {});
            when(shutdownService.isShuttingDown()).thenReturn(false);
            when(commandRepository.findReadyCommands(20)).thenReturn(new ArrayList<>());

            pollingService.poll();

            verify(commandRepository).findProcessingCommands();
        }
    }

    @Nested
    @DisplayName("runTransfer")
    class RunTransferTests {

        @Test
        @DisplayName("should call transferService.process and endTask on success")
        void shouldProcessAndEndTask() {
            pollingService.runTransfer(1L, "UPLOAD");

            verify(transferService).process(1L, "UPLOAD");
            verify(shutdownService).endTask();
        }

        @Test
        @DisplayName("should endTask even when transferService throws")
        void shouldEndTaskOnException() {
            doThrow(new RuntimeException("Process error"))
                    .when(transferService).process(1L, "UPLOAD");

            // Should not propagate exception
            pollingService.runTransfer(1L, "UPLOAD");

            verify(transferService).process(1L, "UPLOAD");
            verify(shutdownService).endTask();
        }

        @Test
        @DisplayName("should endTask when transferService throws Error")
        void shouldEndTaskOnError() {
            doThrow(new OutOfMemoryError("OOM"))
                    .when(transferService).process(1L, "UPLOAD");

            pollingService.runTransfer(1L, "UPLOAD");

            verify(transferService).process(1L, "UPLOAD");
            verify(shutdownService).endTask();
        }
    }

    @Nested
    @DisplayName("createThreadPoolBatchExecutor")
    class CreateThreadPoolBatchExecutorTests {

        @Test
        @DisplayName("should create executor with specified batch size")
        void shouldCreateExecutorWithBatchSize() {
            ExecutorService executor = PollingService.createThreadPoolBatchExecutor(5);
            assertNotNull(executor);
            assertFalse(executor.isShutdown());
            executor.shutdown();
        }

        @Test
        @DisplayName("should create executor with minimum size 1 for zero input")
        void shouldCreateExecutorWithMinSize() {
            ExecutorService executor = PollingService.createThreadPoolBatchExecutor(0);
            assertNotNull(executor);
            assertFalse(executor.isShutdown());
            executor.shutdown();
        }

        @Test
        @DisplayName("should create executor with minimum size 1 for negative input")
        void shouldCreateExecutorWithMinSizeForNegative() {
            ExecutorService executor = PollingService.createThreadPoolBatchExecutor(-5);
            assertNotNull(executor);
            executor.shutdown();
        }
    }
}
