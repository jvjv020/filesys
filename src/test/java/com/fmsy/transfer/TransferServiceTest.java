package com.fmsy.transfer;

import com.fmsy.config.AppConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.lifecycle.ConfigLoaderService;
import com.fmsy.model.Command;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.download.multi.ChildBucketProcessor;
import com.fmsy.repository.CommandRepository;
import com.fmsy.transfer.TransferOrchestrator;
import com.fmsy.util.ColumnNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferService Tests")
class TransferServiceTest {

    @Mock
    private TransferOrchestrator transferOrchestrator;

    @Mock
    private ConfigLoaderService configLoader;

    @Mock
    private ChildBucketProcessor childBucketProcessor;

    @Mock
    private AppConfig appConfig;

    @Mock
    private CommandRepository commandRepository;

    @Mock
    private TempTransferConfigFactory tempConfigFactory;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(transferOrchestrator, configLoader,
                childBucketProcessor, appConfig, commandRepository, tempConfigFactory);
        when(appConfig.getNodeId()).thenReturn("node-1");
    }

    @Nested
    @DisplayName("process - command not found")
    class ProcessCommandNotFound {

        @Test
        @DisplayName("should mark error when command is null")
        void shouldMarkErrorWhenCommandIsNull() {
            when(commandRepository.findById(1L)).thenReturn(null);

            transferService.process(1L, "DOWNLOAD");

            verify(commandRepository).markErrorWithResult(1L, null, null,
                    "Command disappeared after compete");
            verifyNoInteractions(transferOrchestrator);
        }
    }

    @Nested
    @DisplayName("process - TEMPORARY command type")
    class ProcessTemporaryCommand {

        @Test
        @DisplayName("should build temp config and execute")
        void shouldBuildTempConfigAndExecute() throws Exception {
            Command command = new Command();
            command.setId(1L);
            command.setCategoryCode("CAT001");
            command.setControlCode("CTRL001");
            command.setCommandType(CommandType.TEMPORARY);

            TransferConfig config = new TransferConfig();
            config.setCategoryCode("CAT001");
            config.setControlCode("CTRL001");

            when(commandRepository.findById(1L)).thenReturn(command);
            when(tempConfigFactory.build(command)).thenReturn(config);

            transferService.process(1L, "UPLOAD");

            verify(tempConfigFactory).build(command);
            verify(transferOrchestrator).execute(command, config);
            verify(configLoader, never()).getConfigOrDefault(any(), any());
        }

        @Test
        @DisplayName("should mark error when temp config build fails")
        void shouldMarkErrorWhenTempConfigBuildFails() {
            Command command = new Command();
            command.setId(1L);
            command.setCategoryCode("CAT001");
            command.setControlCode("CTRL001");
            command.setCommandType(CommandType.TEMPORARY);

            when(commandRepository.findById(1L)).thenReturn(command);
            when(tempConfigFactory.build(command)).thenThrow(
                    new RuntimeException("Invalid JSON"));

            transferService.process(1L, "UPLOAD");

            verify(commandRepository).markErrorWithResult(1L, "CAT001", "CTRL001",
                    "Temp config error: Invalid JSON");
            verifyNoInteractions(transferOrchestrator);
        }
    }

    @Nested
    @DisplayName("process - config lookup")
    class ProcessConfigLookup {

        @Test
        @DisplayName("should use configLoader when not TEMPORARY")
        void shouldUseConfigLoaderWhenNotTemporary() throws Exception {
            Command command = new Command();
            command.setId(1L);
            command.setCategoryCode("CAT001");
            command.setControlCode("CTRL001");
            command.setCommandType(CommandType.SERIAL);

            TransferConfig config = new TransferConfig();
            config.setCategoryCode("CAT001");
            config.setControlCode("CTRL001");

            when(commandRepository.findById(1L)).thenReturn(command);
            when(configLoader.getConfigOrDefault("CAT001", "CTRL001")).thenReturn(config);

            transferService.process(1L, "UPLOAD");

            verify(configLoader).getConfigOrDefault("CAT001", "CTRL001");
            verify(transferOrchestrator).execute(command, config);
        }

        @Test
        @DisplayName("should mark error when config is null")
        void shouldMarkErrorWhenConfigIsNull() {
            Command command = new Command();
            command.setId(1L);
            command.setCategoryCode("CAT001");
            command.setControlCode("CTRL001");
            command.setCommandType(CommandType.SERIAL);

            when(commandRepository.findById(1L)).thenReturn(command);
            when(configLoader.getConfigOrDefault("CAT001", "CTRL001")).thenReturn(null);

            transferService.process(1L, "UPLOAD");

            verify(commandRepository).markErrorWithResult(1L, "CAT001", "CTRL001",
                    "Config not found: CAT001_CTRL001");
            verifyNoInteractions(transferOrchestrator);
        }
    }

    @Nested
    @DisplayName("process - COORDINATED command routing")
    class ProcessCoordinatedRouting {

        @Test
        @DisplayName("should route COORDINATED DOWNLOAD to ChildBucketProcessor")
        void shouldRouteCoordinatedDownloadToChildBucketProcessor() {
            Command command = new Command();
            command.setId(2L);
            command.setCategoryCode("CAT001");
            command.setControlCode("CTRL001");
            command.setCommandType(CommandType.COORDINATED);
            command.setExtraInfo("1|/data/file.csv");

            TransferConfig config = new TransferConfig();
            config.setCategoryCode("CAT001");
            config.setControlCode("CTRL001");

            when(commandRepository.findById(2L)).thenReturn(command);
            when(configLoader.getConfigOrDefault("CAT001", "CTRL001")).thenReturn(config);

            transferService.process(2L, "DOWNLOAD");

            verify(childBucketProcessor).pollAndProcess("node-1", command, config);
            verifyNoInteractions(transferOrchestrator);
        }

        @Test
        @DisplayName("should NOT route COORDINATED to ChildBucketProcessor for UPLOAD")
        void shouldNotRouteCoordinatedUploadToChildBucketProcessor() throws Exception {
            Command command = new Command();
            command.setId(5L);
            command.setCategoryCode("CAT001");
            command.setControlCode("CTRL001");
            command.setCommandType(CommandType.COORDINATED);

            TransferConfig config = new TransferConfig();
            config.setCategoryCode("CAT001");
            config.setControlCode("CTRL001");

            when(commandRepository.findById(5L)).thenReturn(command);
            when(configLoader.getConfigOrDefault("CAT001", "CTRL001")).thenReturn(config);

            transferService.process(5L, "UPLOAD");

            // Non-download COORDINATED goes to orchestrator
            verify(transferOrchestrator).execute(command, config);
            verifyNoInteractions(childBucketProcessor);
        }
    }

    @Nested
    @DisplayName("process - normal SERIAL command")
    class ProcessSerialCommand {

        @Test
        @DisplayName("should execute SERIAL command via orchestrator")
        void shouldExecuteSerialViaOrchestrator() throws Exception {
            Command command = new Command();
            command.setId(10L);
            command.setCategoryCode("CAT002");
            command.setControlCode("CTRL002");
            command.setCommandType(CommandType.SERIAL);

            TransferConfig config = new TransferConfig();
            config.setCategoryCode("CAT002");
            config.setControlCode("CTRL002");

            when(commandRepository.findById(10L)).thenReturn(command);
            when(configLoader.getConfigOrDefault("CAT002", "CTRL002")).thenReturn(config);

            transferService.process(10L, "UPLOAD");

            verify(transferOrchestrator).execute(command, config);
        }
    }

    @Nested
    @DisplayName("process - lifecycle")
    class ProcessLifecycle {

        @Test
        @DisplayName("should call markStartTimeIfAbsent on command")
        void shouldCallMarkStartTimeIfAbsent() {
            Command command = spy(new Command());
            command.setId(1L);
            command.setCategoryCode("CAT001");
            command.setControlCode("CTRL001");
            command.setCommandType(CommandType.SERIAL);

            when(commandRepository.findById(1L)).thenReturn(command);
            when(configLoader.getConfigOrDefault("CAT001", "CTRL001")).thenReturn(null);

            transferService.process(1L, "UPLOAD");

            verify(command).markStartTimeIfAbsent();
        }

        @Test
        @DisplayName("should catch exception and not propagate")
        void shouldCatchExceptionAndNotPropagate() {
            when(commandRepository.findById(1L)).thenThrow(new RuntimeException("DB error"));

            assertDoesNotThrow(() -> transferService.process(1L, "UPLOAD"));
        }
    }
}
