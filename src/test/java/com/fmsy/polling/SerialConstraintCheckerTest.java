package com.fmsy.polling;

import com.fmsy.config.AppConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.lifecycle.ConfigLoaderService;
import com.fmsy.model.Command;
import com.fmsy.model.TransferConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SerialConstraintChecker Tests")
class SerialConstraintCheckerTest {

    @Mock
    private ConfigLoaderService configLoader;

    @Mock
    private AppConfig appConfig;

    @Mock
    private AppConfig.Node nodeConfig;

    private SerialConstraintChecker checker;

    @BeforeEach
    void setUp() {
        when(appConfig.getNode()).thenReturn(nodeConfig);
        when(nodeConfig.getId()).thenReturn("node1");
        checker = new SerialConstraintChecker(configLoader, appConfig);
    }

    private Command createCommand(String category, String control, CommandType type) {
        Command cmd = new Command();
        cmd.setCategoryCode(category);
        cmd.setControlCode(control);
        cmd.setCommandType(type);
        return cmd;
    }

    private CommandProcessingTracker createTracker(boolean hasSType, String mainId) {
        CommandProcessingTracker tracker = new CommandProcessingTracker();
        tracker.setHasSType(hasSType);
        tracker.setMainCommandId(mainId);
        return tracker;
    }

    @Nested
    @DisplayName("TEMPORARY command type")
    class TemporaryCommandTests {

        @Test
        @DisplayName("should always allow TEMPORARY commands")
        void shouldAllowTemporaryCommands() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.TEMPORARY);
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();

            boolean result = checker.check(command, processingMap);

            assertTrue(result);
            verifyNoInteractions(configLoader);
        }
    }

    @Nested
    @DisplayName("No config scenario")
    class NoConfigTests {

        @Test
        @DisplayName("should allow command when no config exists")
        void shouldAllowWhenNoConfig() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.SERIAL);
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(null);
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();

            boolean result = checker.check(command, processingMap);

            assertTrue(result);
        }

        @Test
        @DisplayName("should allow command when config is null and processing map is empty")
        void shouldAllowWhenConfigNullAndMapEmpty() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.BATCH);
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(null);
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();

            boolean result = checker.check(command, processingMap);

            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("Serial mode (serialFlag=Y)")
    class SerialModeTests {

        @Test
        @DisplayName("should allow when no other node is processing")
        void shouldAllowWhenNoOtherNodeProcessing() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.SERIAL);
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("Y");
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(config);
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();

            boolean result = checker.check(command, processingMap);

            assertTrue(result);
        }

        @Test
        @DisplayName("should deny when another node is already processing")
        void shouldDenyWhenAnotherNodeProcessing() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.SERIAL);
            when(appConfig.getNode()).thenReturn(nodeConfig);
            when(nodeConfig.getId()).thenReturn("node1");
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("Y");
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(config);
            CommandProcessingTracker tracker = new CommandProcessingTracker();
            tracker.addNode("node2");
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();
            processingMap.put("CAT1_CTRL1", tracker);

            boolean result = checker.check(command, processingMap);

            assertFalse(result);
        }

        @Test
        @DisplayName("should allow when same node is already processing with serial mode")
        void shouldAllowSameNodeInSerialMode() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.SERIAL);
            when(appConfig.getNode()).thenReturn(nodeConfig);
            when(nodeConfig.getId()).thenReturn("node1");
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("Y");
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(config);
            CommandProcessingTracker tracker = new CommandProcessingTracker();
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();
            processingMap.put("CAT1_CTRL1", tracker);

            boolean result = checker.check(command, processingMap);

            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("Non-serial mode (serialFlag=N)")
    class NonSerialModeTests {

        @Test
        @DisplayName("should allow when no node is processing")
        void shouldAllowWhenNoNodeProcessing() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.SERIAL);
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("N");
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(config);
            when(appConfig.getNode()).thenReturn(nodeConfig);
            when(nodeConfig.getId()).thenReturn("node1");
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();

            boolean result = checker.check(command, processingMap);

            assertTrue(result);
        }

        @Test
        @DisplayName("should deny when same node is already processing")
        void shouldDenyWhenSameNodeProcessing() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.SERIAL);
            when(appConfig.getNode()).thenReturn(nodeConfig);
            when(nodeConfig.getId()).thenReturn("node1");
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("N");
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(config);
            CommandProcessingTracker tracker = new CommandProcessingTracker();
            tracker.addNode("node1");
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();
            processingMap.put("CAT1_CTRL1", tracker);

            boolean result = checker.check(command, processingMap);

            assertFalse(result);
        }

        @Test
        @DisplayName("should allow when different node is processing")
        void shouldAllowWhenDifferentNodeProcessing() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.SERIAL);
            when(appConfig.getNode()).thenReturn(nodeConfig);
            when(nodeConfig.getId()).thenReturn("node1");
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("N");
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(config);
            CommandProcessingTracker tracker = new CommandProcessingTracker();
            tracker.addNode("node2");
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();
            processingMap.put("CAT1_CTRL1", tracker);

            boolean result = checker.check(command, processingMap);

            assertTrue(result);
        }

        @Test
        @DisplayName("should allow when nodeId is null in non-serial mode")
        void shouldAllowWhenNodeIdNullInNonSerialMode() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.SERIAL);
            when(appConfig.getNode()).thenReturn(nodeConfig);
            when(nodeConfig.getId()).thenReturn(null);
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("N");
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(config);
            CommandProcessingTracker tracker = new CommandProcessingTracker();
            tracker.addNode("node1");
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();
            processingMap.put("CAT1_CTRL1", tracker);

            boolean result = checker.check(command, processingMap);

            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("COORDINATED (S-type) command special handling")
    class CoordinatedCommandTests {

        @Test
        @DisplayName("should allow COORDINATED command when no tracker exists")
        void shouldAllowCoordinatedWhenNoTracker() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.COORDINATED);
            command.setExtraInfo("main123");
            when(appConfig.getNode()).thenReturn(nodeConfig);
            when(nodeConfig.getId()).thenReturn("node1");
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(null);
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();

            boolean result = checker.check(command, processingMap);

            assertTrue(result);
        }

        @Test
        @DisplayName("should allow COORDINATED command from same main command")
        void shouldAllowCoordinatedSameMain() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.COORDINATED);
            command.setExtraInfo("main123");
            when(appConfig.getNode()).thenReturn(nodeConfig);
            when(nodeConfig.getId()).thenReturn("node1");
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("Y");
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(config);
            CommandProcessingTracker tracker = new CommandProcessingTracker();
            tracker.setHasSType(true);
            tracker.setMainCommandId("main123");
            tracker.recordMainId("node1", "main123");
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();
            processingMap.put("CAT1_CTRL1", tracker);

            boolean result = checker.check(command, processingMap);

            assertTrue(result);
        }

        @Test
        @DisplayName("should deny COORDINATED command from different main command")
        void shouldDenyCoordinatedDifferentMain() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.COORDINATED);
            command.setExtraInfo("main456");
            when(appConfig.getNode()).thenReturn(nodeConfig);
            when(nodeConfig.getId()).thenReturn("node1");
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("Y");
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(config);
            CommandProcessingTracker tracker = new CommandProcessingTracker();
            tracker.setHasSType(true);
            tracker.setMainCommandId("main123");
            tracker.recordMainId("node1", "main123");
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();
            processingMap.put("CAT1_CTRL1", tracker);

            boolean result = checker.check(command, processingMap);

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("processingMap update logic")
    class ProcessingMapUpdateTests {

        @Test
        @DisplayName("should create key from category and control code")
        void shouldCreateKeyFromCategoryAndControl() {
            Command command = createCommand("MYCAT", "MYCTRL", CommandType.SERIAL);
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("N");
            when(configLoader.getConfig("MYCAT", "MYCTRL")).thenReturn(config);
            when(appConfig.getNode()).thenReturn(nodeConfig);
            when(nodeConfig.getId()).thenReturn("node1");
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();

            checker.check(command, processingMap);

            verify(configLoader).getConfig("MYCAT", "MYCTRL");
        }

        @Test
        @DisplayName("should return true for empty processing map")
        void shouldReturnTrueForEmptyMap() {
            Command command = createCommand("CAT1", "CTRL1", CommandType.SERIAL);
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("Y");
            when(configLoader.getConfig("CAT1", "CTRL1")).thenReturn(config);
            Map<String, CommandProcessingTracker> processingMap = new HashMap<>();

            boolean result = checker.check(command, processingMap);

            assertTrue(result);
        }
    }
}
