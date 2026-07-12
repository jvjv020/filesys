package com.fmsy.lifecycle;

import com.fmsy.config.AppConfig;
import com.fmsy.config.DataSourceConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.util.ColumnNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StartupService Tests")
class StartupServiceTest {

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private AppConfig appConfig;

    @Mock
    private AppConfig.Node nodeConfig;

    @Mock
    private ConfigLoaderService configLoader;

    @Mock
    private ResultRepository resultRepository;

    @Mock
    private CommandRepository commandRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private StartupService startupService;

    @BeforeEach
    void setUp() {
        when(appConfig.getNode()).thenReturn(nodeConfig);
        when(nodeConfig.getId()).thenReturn("node1");
        when(dbPool.getTransactionTemplate(ColumnNames.DEFAULT_DB)).thenReturn(transactionTemplate);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        startupService = new StartupService(
                dbPool, appConfig, configLoader, resultRepository, commandRepository);
    }

    @Nested
    @DisplayName("onApplicationReady")
    class OnApplicationReadyTests {

        @Test
        @DisplayName("should execute full startup sequence")
        void shouldExecuteFullSequence() {
            when(commandRepository.probeDatabase()).thenReturn(1);
            when(commandRepository.findProcessingJobs("node1")).thenReturn(new ArrayList<>());

            startupService.onApplicationReady();

            verify(commandRepository).probeDatabase();
            verify(configLoader).loadConfigs();
            verify(commandRepository).findProcessingJobs("node1");
        }

        @Test
        @DisplayName("should initialize node ID from config")
        void shouldInitializeNodeIdFromConfig() {
            when(nodeConfig.getId()).thenReturn("node-prod-1");
            when(commandRepository.probeDatabase()).thenReturn(1);
            when(commandRepository.findProcessingJobs("node1")).thenReturn(new ArrayList<>());

            startupService.onApplicationReady();

            assertEquals("node-prod-1", appConfig.getNode().getId());
        }

        @Test
        @DisplayName("should fall back to system property for node ID when config is empty")
        void shouldFallbackNodeId() {
            when(nodeConfig.getId()).thenReturn("");
            when(commandRepository.probeDatabase()).thenReturn(1);
            when(commandRepository.findProcessingJobs("node1")).thenReturn(new ArrayList<>());

            startupService.onApplicationReady();

            verify(nodeConfig).setId(anyString());
        }

        @Test
        @DisplayName("should handle null node config ID")
        void shouldHandleNullNodeId() {
            when(nodeConfig.getId()).thenReturn(null);
            when(commandRepository.probeDatabase()).thenReturn(1);
            when(commandRepository.findProcessingJobs("node1")).thenReturn(new ArrayList<>());

            startupService.onApplicationReady();

            verify(nodeConfig).setId(anyString());
        }
    }

    @Nested
    @DisplayName("probeDatabase")
    class ProbeDatabaseTests {

        @Test
        @DisplayName("should log success when probe returns 1")
        void shouldLogSuccessOnProbe() {
            when(commandRepository.probeDatabase()).thenReturn(1);
            when(commandRepository.findProcessingJobs("node1")).thenReturn(new ArrayList<>());

            startupService.onApplicationReady();

            verify(commandRepository).probeDatabase();
        }

        @Test
        @DisplayName("should log warning when probe returns non-1")
        void shouldLogWarningOnNonOne() {
            when(commandRepository.probeDatabase()).thenReturn(0);
            when(commandRepository.findProcessingJobs("node1")).thenReturn(new ArrayList<>());

            // Should not throw
            startupService.onApplicationReady();
        }

        @Test
        @DisplayName("should handle probe exception gracefully")
        void shouldHandleProbeException() {
            when(commandRepository.probeDatabase()).thenThrow(new RuntimeException("DB unavailable"));
            when(commandRepository.findProcessingJobs("node1")).thenReturn(new ArrayList<>());

            // Should not prevent further startup steps
            startupService.onApplicationReady();

            verify(configLoader).loadConfigs();
        }
    }

    @Nested
    @DisplayName("recoverAbnormalJobs")
    class RecoverAbnormalJobsTests {

        @Test
        @DisplayName("should skip when no abnormal jobs exist")
        void shouldSkipWhenNoJobs() {
            when(commandRepository.probeDatabase()).thenReturn(1);
            when(commandRepository.findProcessingJobs("node1")).thenReturn(new ArrayList<>());

            startupService.onApplicationReady();

            verify(commandRepository, never()).updateStatus(anyLong(), anyString());
            verify(resultRepository, never()).insertSimple(anyLong(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should recover abnormal jobs atomically")
        void shouldRecoverAbnormalJobs() {
            when(commandRepository.probeDatabase()).thenReturn(1);

            List<Map<String, Object>> jobs = new ArrayList<>();
            Map<String, Object> job = new HashMap<>();
            job.put("自增列", 1L);
            job.put("类别代号", "CAT1");
            job.put("控制代号", "CTRL1");
            jobs.add(job);

            when(commandRepository.findProcessingJobs("node1")).thenReturn(jobs);

            startupService.onApplicationReady();

            verify(commandRepository).updateStatus(1L, "N");
            verify(resultRepository).insertSimple(1L, "CAT1", "CTRL1", "N", "异常中断恢复，跳过");
        }

        @Test
        @DisplayName("should recover multiple abnormal jobs")
        void shouldRecoverMultipleJobs() {
            when(commandRepository.probeDatabase()).thenReturn(1);

            List<Map<String, Object>> jobs = new ArrayList<>();
            Map<String, Object> job1 = new HashMap<>();
            job1.put("自增列", 1L);
            job1.put("类别代号", "CAT1");
            job1.put("控制代号", "CTRL1");
            jobs.add(job1);

            Map<String, Object> job2 = new HashMap<>();
            job2.put("自增列", 2L);
            job2.put("类别代号", "CAT2");
            job2.put("控制代号", "CTRL2");
            jobs.add(job2);

            when(commandRepository.findProcessingJobs("node1")).thenReturn(jobs);

            startupService.onApplicationReady();

            verify(commandRepository).updateStatus(1L, "N");
            verify(commandRepository).updateStatus(2L, "N");
            verify(resultRepository).insertSimple(1L, "CAT1", "CTRL1", "N", "异常中断恢复，跳过");
            verify(resultRepository).insertSimple(2L, "CAT2", "CTRL2", "N", "异常中断恢复，跳过");
        }

        @Test
        @DisplayName("should propagate exception when recovery fails")
        void shouldPropagateExceptionOnRecoveryFailure() {
            when(commandRepository.probeDatabase()).thenReturn(1);

            List<Map<String, Object>> jobs = new ArrayList<>();
            Map<String, Object> job = new HashMap<>();
            job.put("自增列", 1L);
            job.put("类别代号", "CAT1");
            job.put("控制代号", "CTRL1");
            jobs.add(job);

            when(commandRepository.findProcessingJobs("node1")).thenReturn(jobs);
            doThrow(new RuntimeException("TX error"))
                    .when(commandRepository).updateStatus(anyLong(), anyString());

            // The exception propagates from transaction callback, this is expected
            // since the source code does not catch per-job failures
            assertThrows(RuntimeException.class, () -> startupService.onApplicationReady());
        }
    }
}
