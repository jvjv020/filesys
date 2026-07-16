package com.fmsy.transfer;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.TransferScenario;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.transfer.download.MultiNodeDownloadHandler;
import com.fmsy.transfer.download.SingleDownloadHandler;
import com.fmsy.transfer.download.SingleNodeDownloadHandler;
import com.fmsy.transfer.upload.MultiUploadHandler;
import com.fmsy.transfer.upload.SingleUploadHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferOrchestrator Tests")
class TransferOrchestratorTest {

    @Mock
    private SingleUploadHandler singleUpload;

    @Mock
    private MultiUploadHandler multiUpload;

    @Mock
    private SingleDownloadHandler singleDownload;

    @Mock
    private SingleNodeDownloadHandler singleNodeDownload;

    @Mock
    private MultiNodeDownloadHandler multiNodeDownload;

    @Mock
    private CommandRepository commandRepository;

    @Mock
    private ResultRepository resultRepository;

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private TransactionTemplate transactionTemplate;

    private TransferOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new TransferOrchestrator(singleUpload, multiUpload,
                singleDownload, singleNodeDownload, multiNodeDownload,
                commandRepository, resultRepository, dbPool);
        when(dbPool.getTransactionTemplate(any())).thenReturn(transactionTemplate);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            var callback = invocation.getArgument(0,
                    org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(null);
        });
    }

    @Nested
    @DisplayName("dispatch - UPLOAD_SINGLE")
    class DispatchUploadSingle {

        @Test
        @DisplayName("should route to SingleUploadHandler")
        void shouldRouteToSingleUploadHandler() throws Exception {
            Command command = new Command();
            command.setId(1L);
            command.setCommandType(CommandType.SERIAL);

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.UPLOAD_SINGLE);

            orchestrator.execute(command, config);

            verify(singleUpload).handle(eq(command), eq(config), any(Result.class));
        }
    }

    @Nested
    @DisplayName("dispatch - UPLOAD_MULTI")
    class DispatchUploadMulti {

        @Test
        @DisplayName("BATCH 应路由到 SingleUploadHandler")
        void shouldRouteBatchToSingleUploadHandler() throws Exception {
            Command command = new Command();
            command.setId(2L);
            command.setCommandType(CommandType.BATCH);

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.UPLOAD_MULTI);

            orchestrator.execute(command, config);

            verify(singleUpload).handle(eq(command), eq(config), any(Result.class));
            verify(multiUpload, never()).handle(any(), any(), any());
        }

        @Test
        @DisplayName("SERIAL 应路由到 MultiUploadHandler")
        void shouldRouteSerialToMultiUploadHandler() throws Exception {
            Command command = new Command();
            command.setId(3L);
            command.setCommandType(CommandType.SERIAL);

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.UPLOAD_MULTI);

            orchestrator.execute(command, config);

            verify(multiUpload).handle(eq(command), eq(config), any(Result.class));
            verify(singleUpload, never()).handle(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("dispatch - DOWNLOAD_SINGLE")
    class DispatchDownloadSingle {

        @Test
        @DisplayName("should route to SingleDownloadHandler")
        void shouldRouteToSingleDownloadHandler() throws Exception {
            Command command = new Command();
            command.setId(3L);
            command.setCommandType(CommandType.SERIAL);

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.DOWNLOAD_SINGLE);

            orchestrator.execute(command, config);

            verify(singleDownload).handle(eq(command), eq(config), any(Result.class));
        }
    }

    @Nested
    @DisplayName("dispatch - DOWNLOAD_SINGLE_NODE")
    class DispatchDownloadSingleNode {

        @Test
        @DisplayName("should route to SingleNodeDownloadHandler")
        void shouldRouteToSingleNodeDownloadHandler() throws Exception {
            Command command = new Command();
            command.setId(4L);
            command.setCommandType(CommandType.SERIAL);

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.DOWNLOAD_SINGLE_NODE);

            orchestrator.execute(command, config);

            verify(singleNodeDownload).handle(eq(command), eq(config), any(Result.class));
        }
    }

    @Nested
    @DisplayName("dispatch - DOWNLOAD_MULTI_NODE")
    class DispatchDownloadMultiNode {

        @Test
        @DisplayName("should route to MultiNodeDownloadHandler")
        void shouldRouteToMultiNodeDownloadHandler() throws Exception {
            Command command = new Command();
            command.setId(5L);
            command.setCommandType(CommandType.SERIAL);

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.DOWNLOAD_MULTI_NODE);

            orchestrator.execute(command, config);

            verify(multiNodeDownload).handle(eq(command), eq(config), any(Result.class));
        }
    }

    @Nested
    @DisplayName("dispatch - error handling")
    class DispatchErrorHandling {

        @Test
        @DisplayName("should call failWith and finalize when handler throws")
        void shouldHandleHandlerException() throws Exception {
            Command command = new Command();
            command.setId(6L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.UPLOAD_SINGLE);
            config.setDbName("DB");
            config.setFilePath("/path");
            config.setFtpName("ftp1");
            config.setTableName("mytable");

            doThrow(new RuntimeException("Handler error")).when(singleUpload)
                    .handle(eq(command), eq(config), any(Result.class));

            orchestrator.execute(command, config);

            verify(singleUpload).handle(eq(command), eq(config), any(Result.class));
            verify(commandRepository).updateStatus(anyLong(), anyString());
            verify(resultRepository).insert(any(Result.class));
        }
    }
}
