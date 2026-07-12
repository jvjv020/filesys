package com.fmsy.transfer;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.enums.TransferScenario;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.transfer.download.ChildCommandMonitor;
import com.fmsy.util.ColumnNames;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AbstractTransferOrchestrator Tests")
class AbstractTransferOrchestratorTest {

    @Mock
    private CommandRepository commandRepository;

    @Mock
    private ResultRepository resultRepository;

    @Mock
    private ChildCommandMonitor childCommandMonitor;

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private TransactionTemplate transactionTemplate;

    private TestOrchestrator testOrchestrator;

    @BeforeEach
    void setUp() {
        testOrchestrator = new TestOrchestrator(commandRepository, resultRepository,
                childCommandMonitor, dbPool);
        when(dbPool.getTransactionTemplate(any())).thenReturn(transactionTemplate);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            var callback = invocation.getArgument(0,
                    org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    /**
     * Concrete subclass for testing the abstract template.
     */
    static class TestOrchestrator extends AbstractTransferOrchestrator {
        private boolean throwInDispatch = false;
        private boolean dispatchCalled = false;

        TestOrchestrator(CommandRepository commandRepository,
                         ResultRepository resultRepository,
                         ChildCommandMonitor childCommandMonitor,
                         DataSourceConfig.DbPool dbPool) {
            super(commandRepository, resultRepository, childCommandMonitor, dbPool);
        }

        void setThrowInDispatch(boolean throwInDispatch) {
            this.throwInDispatch = throwInDispatch;
        }

        boolean isDispatchCalled() {
            return dispatchCalled;
        }

        @Override
        protected void dispatch(Command command, TransferConfig config, Result result) throws Exception {
            dispatchCalled = true;
            if (throwInDispatch) {
                throw new RuntimeException("Dispatch error");
            }
            result.setOutcome(100, ColumnNames.STATUS_SUCCESS, "");
        }
    }

    @Nested
    @DisplayName("execute - normal flow")
    class ExecuteNormalFlow {

        @Test
        @DisplayName("should call dispatch and finalize")
        void shouldCallDispatchAndFinalize() {
            Command command = new Command();
            command.setId(1L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.UPLOAD_SINGLE);
            config.setDbName("DB1");
            config.setFilePath("/path");
            config.setFtpName("ftp1");
            config.setTableName("table1");

            testOrchestrator.execute(command, config);

            assertTrue(testOrchestrator.isDispatchCalled());
            verify(commandRepository).updateStatus(eq(1L), eq(ColumnNames.STATUS_SUCCESS));
            verify(resultRepository).insert(any(Result.class));
        }

        @Test
        @DisplayName("should create result with correct direction for UPLOAD")
        void shouldCreateResultWithUploadDirection() {
            Command command = new Command();
            command.setId(1L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.UPLOAD_MULTI);
            config.setDbName("DB1");
            config.setFilePath("/path");
            config.setFtpName("ftp1");
            config.setTableName("table1");

            testOrchestrator.execute(command, config);

            ArgumentCaptor<Result> resultCaptor = ArgumentCaptor.forClass(Result.class);
            verify(resultRepository).insert(resultCaptor.capture());
            assertEquals(Result.DIRECTION_UPLOAD, resultCaptor.getValue().getTransferDirection());
        }

        @Test
        @DisplayName("should create result with correct direction for DOWNLOAD")
        void shouldCreateResultWithDownloadDirection() {
            Command command = new Command();
            command.setId(2L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.DOWNLOAD_SINGLE);
            config.setDbName("DB1");
            config.setFilePath("/path");
            config.setFtpName("ftp1");
            config.setTableName("table1");

            testOrchestrator.execute(command, config);

            ArgumentCaptor<Result> resultCaptor = ArgumentCaptor.forClass(Result.class);
            verify(resultRepository).insert(resultCaptor.capture());
            assertEquals(Result.DIRECTION_DOWNLOAD, resultCaptor.getValue().getTransferDirection());
        }
    }

    @Nested
    @DisplayName("execute - error handling")
    class ExecuteErrorHandling {

        @Test
        @DisplayName("should call failWith and finalize when dispatch throws")
        void shouldCallFailWithAndFinalizeWhenDispatchThrows() {
            testOrchestrator.setThrowInDispatch(true);

            Command command = new Command();
            command.setId(3L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.UPLOAD_SINGLE);
            config.setDbName("DB1");
            config.setFilePath("/path");
            config.setFtpName("ftp1");
            config.setTableName("table1");

            testOrchestrator.execute(command, config);

            assertTrue(testOrchestrator.isDispatchCalled());
            verify(commandRepository).updateStatus(eq(3L), eq(ColumnNames.STATUS_ERROR));
            verify(resultRepository).insert(any(Result.class));
        }

        @Test
        @DisplayName("should not update status when suppressStatusUpdate is true")
        void shouldNotUpdateStatusWhenSuppress() {
            Command command = new Command();
            command.setId(4L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.DOWNLOAD_MULTI_NODE);
            config.setDbName("DB1");
            config.setFilePath("/path");
            config.setFtpName("ftp1");
            config.setTableName("table1");

            // Use orchestrator that marks children created
            TestOrchestrator withMonitor = new TestOrchestrator(commandRepository,
                    resultRepository, childCommandMonitor, dbPool) {
                @Override
                protected void dispatch(Command command, TransferConfig config, Result result) {
                    result.markChildrenCreated(3);
                }
            };

            withMonitor.execute(command, config);

            verify(commandRepository, never()).updateStatus(anyLong(), anyString());
            verify(resultRepository, never()).insert(any(Result.class));
        }
    }

    @Nested
    @DisplayName("execute - child monitor")
    class ExecuteChildMonitor {

        @Test
        @DisplayName("should start child monitor when needsChildMonitor is true")
        void shouldStartChildMonitorWhenNeeded() {
            Command command = new Command();
            command.setId(5L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.DOWNLOAD_MULTI_NODE);
            config.setDbName("DB1");
            config.setFilePath("/path");
            config.setFtpName("ftp1");
            config.setTableName("table1");

            TestOrchestrator withMonitor = new TestOrchestrator(commandRepository,
                    resultRepository, childCommandMonitor, dbPool) {
                @Override
                protected void dispatch(Command command, TransferConfig config, Result result) {
                    result.markChildrenCreated(3);
                }
            };

            withMonitor.execute(command, config);

            verify(childCommandMonitor).start(5L, 3, "DB1");
        }

        @Test
        @DisplayName("should not start child monitor when needsChildMonitor is false")
        void shouldNotStartChildMonitorWhenNotNeeded() {
            Command command = new Command();
            command.setId(6L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.UPLOAD_SINGLE);
            config.setDbName("DB1");
            config.setFilePath("/path");
            config.setFtpName("ftp1");
            config.setTableName("table1");

            testOrchestrator.execute(command, config);

            verifyNoInteractions(childCommandMonitor);
        }

        @Test
        @DisplayName("should handle child monitor start failure gracefully")
        void shouldHandleMonitorStartFailure() {
            Command command = new Command();
            command.setId(7L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.DOWNLOAD_MULTI_NODE);
            config.setDbName("DB1");
            config.setFilePath("/path");
            config.setFtpName("ftp1");
            config.setTableName("table1");

            doThrow(new RuntimeException("Monitor error")).when(childCommandMonitor)
                    .start(anyLong(), anyInt(), anyString());

            TestOrchestrator withMonitor = new TestOrchestrator(commandRepository,
                    resultRepository, childCommandMonitor, dbPool) {
                @Override
                protected void dispatch(Command command, TransferConfig config, Result result) {
                    result.markChildrenCreated(3);
                }
            };

            // Should not throw
            withMonitor.execute(command, config);
        }
    }
}
