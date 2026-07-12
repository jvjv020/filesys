package com.fmsy.polling;

import com.fmsy.config.AppConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.TransferScenario;
import com.fmsy.lifecycle.ConfigLoaderService;
import com.fmsy.lifecycle.ShutdownService;
import com.fmsy.model.Command;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.transfer.TempTransferConfigFactory;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BatchDispatcher Tests")
class BatchDispatcherTest {

    @Mock
    private AppConfig appConfig;

    @Mock
    private AppConfig.Polling pollingConfig;

    @Mock
    private AppConfig.Node nodeConfig;

    @Mock
    private SerialConstraintChecker constraintChecker;

    @Mock
    private ShutdownService shutdownService;

    @Mock
    private ConfigLoaderService configLoader;

    @Mock
    private TempTransferConfigFactory tempConfigFactory;

    @Mock
    private CommandRepository commandRepository;

    @Mock
    private IntFunction<ExecutorService> batchExecutorFactory;

    @Mock
    private ExecutorService mockExecutor;

    private BatchDispatcher dispatcher;

    private List<Command> emptyCommands;
    private Map<String, CommandProcessingTracker> emptyProcessingMap;
    private BatchDispatcher.TransferRunner transferRunner;

    @BeforeEach
    void setUp() {
        when(appConfig.getNode()).thenReturn(nodeConfig);
        when(nodeConfig.getId()).thenReturn("node1");
        when(appConfig.getPolling()).thenReturn(pollingConfig);
        when(pollingConfig.getBatchSize()).thenReturn(5);

        dispatcher = new BatchDispatcher(
                appConfig, constraintChecker, shutdownService, configLoader,
                tempConfigFactory, commandRepository, batchExecutorFactory);

        emptyCommands = new ArrayList<>();
        emptyProcessingMap = new HashMap<>();
        transferRunner = mock(BatchDispatcher.TransferRunner.class);
    }

    private Command createCommand(Long id, String category, String control, CommandType type) {
        Command cmd = new Command();
        cmd.setId(id);
        cmd.setCategoryCode(category);
        cmd.setControlCode(control);
        cmd.setCommandType(type);
        return cmd;
    }

    private TransferConfig createUploadConfig() {
        TransferConfig config = new TransferConfig();
        config.setScenario(TransferScenario.UPLOAD_SINGLE);
        config.setFtpName("ftp1");
        config.setFilePath("/upload/path");
        return config;
    }

    private TransferConfig createDownloadConfig() {
        TransferConfig config = new TransferConfig();
        config.setScenario(TransferScenario.DOWNLOAD_SINGLE);
        config.setFtpName("ftp1");
        config.setFilePath("/download/path");
        return config;
    }

    @Nested
    @DisplayName("dispatch with empty commands")
    class EmptyCommandTests {

        @Test
        @DisplayName("should return immediately when readyCommands is empty")
        void shouldReturnOnEmptyCommands() {
            dispatcher.dispatch(emptyCommands, emptyProcessingMap, transferRunner);

            verify(constraintChecker, never()).check(any(), any());
            verify(commandRepository, never()).compete(anyLong(), anyString());
            verify(batchExecutorFactory, never()).apply(anyInt());
        }
    }

    @Nested
    @DisplayName("dispatch with commands")
    class DispatchWithCommandsTests {

        @Test
        @DisplayName("should dispatch command through full flow")
        void shouldDispatchThroughFullFlow() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.SERIAL);
            List<Command> commands = List.of(cmd);
            TransferConfig config = createUploadConfig();

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(true);
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(config);
            when(batchExecutorFactory.apply(5)).thenReturn(mockExecutor);
            when(shutdownService.beginTask()).thenReturn(true);

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            verify(constraintChecker).check(cmd, emptyProcessingMap);
            verify(commandRepository).compete(1L, "node1");
            verify(configLoader).getConfigOrDefault("CAT1", "CTRL1");
            verify(mockExecutor).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("should skip command when constraint check fails")
        void shouldSkipWhenConstraintFails() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.SERIAL);
            List<Command> commands = List.of(cmd);

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(false);

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            verify(commandRepository, never()).compete(anyLong(), anyString());
        }

        @Test
        @DisplayName("should skip command when competition fails")
        void shouldSkipWhenCompeteFails() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.SERIAL);
            List<Command> commands = List.of(cmd);

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(false);

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            verify(configLoader, never()).getConfigOrDefault(anyString(), anyString());
        }

        @Test
        @DisplayName("should mark error when no config found")
        void shouldMarkErrorWhenNoConfig() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.SERIAL);
            List<Command> commands = List.of(cmd);

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(true);
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(null);

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            verify(commandRepository).markErrorWithResult(1L, "CAT1", "CTRL1",
                    "Config not found: CAT1_CTRL1");
        }

        @Test
        @DisplayName("should stop dispatching when shutdown starts")
        void shouldStopOnShutdown() {
            Command cmd1 = createCommand(1L, "CAT1", "CTRL1", CommandType.SERIAL);
            Command cmd2 = createCommand(2L, "CAT2", "CTRL2", CommandType.SERIAL);
            List<Command> commands = List.of(cmd1, cmd2);

            when(constraintChecker.check(cmd1, emptyProcessingMap)).thenReturn(true);
            when(constraintChecker.check(cmd2, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(true);
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(createUploadConfig());
            when(shutdownService.beginTask()).thenReturn(true);
            when(batchExecutorFactory.apply(5)).thenReturn(mockExecutor);
            // Shutdown begins after first command is handled
            when(shutdownService.isShuttingDown()).thenReturn(false).thenReturn(true);

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            verify(commandRepository).compete(1L, "node1");
            // Second command should not be processed due to shutdown
            verify(commandRepository, never()).compete(eq(2L), anyString());
        }

        @Test
        @DisplayName("should handle RejectedExecutionException gracefully")
        void shouldHandleRejectedExecution() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.SERIAL);
            List<Command> commands = List.of(cmd);

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(true);
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(createUploadConfig());
            when(shutdownService.beginTask()).thenReturn(true);
            when(batchExecutorFactory.apply(5)).thenReturn(mockExecutor);
            doThrow(new java.util.concurrent.RejectedExecutionException("Pool full"))
                    .when(mockExecutor).execute(any(Runnable.class));

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            verify(shutdownService).endTask();
        }

        @Test
        @DisplayName("should beginTask only when dispatch proceeds")
        void shouldBeginTaskOnDispatch() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.SERIAL);
            List<Command> commands = List.of(cmd);

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(true);
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(createUploadConfig());
            when(shutdownService.beginTask()).thenReturn(true);
            when(batchExecutorFactory.apply(5)).thenReturn(mockExecutor);

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            verify(shutdownService).beginTask();
        }

        @Test
        @DisplayName("should skip dispatch when beginTask fails (shutting down)")
        void shouldSkipWhenBeginTaskFails() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.SERIAL);
            List<Command> commands = List.of(cmd);

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(true);
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(createUploadConfig());
            when(shutdownService.beginTask()).thenReturn(false);

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            verify(mockExecutor, never()).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("should handle TEMPORARY command type")
        void shouldHandleTempCommand() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.TEMPORARY);
            List<Command> commands = List.of(cmd);
            TransferConfig config = createUploadConfig();

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(true);
            when(tempConfigFactory.build(cmd)).thenReturn(config);
            when(batchExecutorFactory.apply(5)).thenReturn(mockExecutor);
            when(shutdownService.beginTask()).thenReturn(true);

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            verify(tempConfigFactory).build(cmd);
            verify(configLoader, never()).getConfigOrDefault(anyString(), anyString());
        }

        @Test
        @DisplayName("should mark error when tempConfigFactory throws")
        void shouldHandleTempConfigError() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.TEMPORARY);
            List<Command> commands = List.of(cmd);

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(true);
            when(tempConfigFactory.build(cmd)).thenThrow(new RuntimeException("Invalid config"));

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            verify(commandRepository).markErrorWithResult(1L, "CAT1", "CTRL1",
                    "Temp config error: Invalid config");
        }

        @Test
        @DisplayName("should create executor lazily only when dispatch proceeds")
        void shouldCreateExecutorLazily() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.SERIAL);
            List<Command> commands = List.of(cmd);

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(true);
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(createUploadConfig());
            when(shutdownService.beginTask()).thenReturn(true);
            when(batchExecutorFactory.apply(5)).thenReturn(mockExecutor);

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            verify(batchExecutorFactory).apply(5);
        }

        @Test
        @DisplayName("should set upload direction based on scenario")
        void shouldSetUploadDirection() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.SERIAL);
            List<Command> commands = List.of(cmd);
            TransferConfig config = createUploadConfig();

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(true);
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(config);
            when(batchExecutorFactory.apply(5)).thenReturn(mockExecutor);
            when(shutdownService.beginTask()).thenReturn(true);

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.captor();
            verify(mockExecutor).execute(runnableCaptor.capture());
            runnableCaptor.getValue().run();
            verify(transferRunner).run(1L, "UPLOAD");
        }

        @Test
        @DisplayName("should set download direction based on scenario")
        void shouldSetDownloadDirection() {
            Command cmd = createCommand(1L, "CAT1", "CTRL1", CommandType.SERIAL);
            List<Command> commands = List.of(cmd);
            TransferConfig config = createDownloadConfig();

            when(constraintChecker.check(cmd, emptyProcessingMap)).thenReturn(true);
            when(commandRepository.compete(1L, "node1")).thenReturn(true);
            when(configLoader.getConfigOrDefault("CAT1", "CTRL1")).thenReturn(config);
            when(batchExecutorFactory.apply(5)).thenReturn(mockExecutor);
            when(shutdownService.beginTask()).thenReturn(true);

            dispatcher.dispatch(commands, emptyProcessingMap, transferRunner);

            ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.captor();
            verify(mockExecutor).execute(runnableCaptor.capture());
            runnableCaptor.getValue().run();
            verify(transferRunner).run(1L, "DOWNLOAD");
        }
    }
}
