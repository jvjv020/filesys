package com.fmsy.transfer;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.enums.TransferScenario;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.ResultRepository;
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
@DisplayName("TransferOrchestrator.finalize Tests")
class TransferOrchestratorTest {

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
        // 只用 mock 创建 TransferOrchestrator（dispatch 不会用到 commandRepository/resultRepository）
        // handler 参数传 null，因为 finalize 测试不涉及 dispatch
        orchestrator = new TransferOrchestrator(null, null, null, null, null,
                commandRepository, resultRepository, dbPool);
        when(dbPool.getTransactionTemplate(any())).thenReturn(transactionTemplate);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            var callback = invocation.getArgument(0,
                    org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Nested
    @DisplayName("finalize - normal flow")
    class FinalizeNormalFlow {

        @Test
        @DisplayName("should write status and result")
        void shouldWriteStatusAndResult() {
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

            // 先构建一个与 execute 一致的 Result
            Result result = Result.builder()
                    .transferDirection(Result.DIRECTION_UPLOAD)
                    .markStart()
                    .build();
            result.setOutcome(100, ColumnNames.STATUS_SUCCESS, "");

            orchestrator.finalize(command, config, result);

            verify(commandRepository).updateStatus(eq(1L), eq(ColumnNames.STATUS_SUCCESS));
            verify(resultRepository).insert(any(Result.class));
        }

        @Test
        @DisplayName("should write ERROR status when result has error")
        void shouldWriteErrorStatus() {
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

            Result result = Result.builder()
                    .transferDirection(Result.DIRECTION_UPLOAD)
                    .markStart()
                    .build();
            result.setOutcome(0, ColumnNames.STATUS_ERROR, "Test error");

            orchestrator.finalize(command, config, result);

            verify(commandRepository).updateStatus(eq(3L), eq(ColumnNames.STATUS_ERROR));
            verify(resultRepository).insert(any(Result.class));
        }
    }

    @Nested
    @DisplayName("execute - direction")
    class ExecuteDirection {

        @Test
        @DisplayName("should use UPLOAD direction for upload scenario")
        void shouldUseUploadDirection() {
            // 验证 execute 中 newResult 能正确推导方向
            // 通过 finalize 写入的结果来验证
            Command command = new Command();
            command.setId(4L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.UPLOAD_SINGLE);
            config.setDbName("DB1");
            config.setFilePath("/path");
            config.setFtpName("ftp1");
            config.setTableName("table1");

            // 手动构造一个"dispatch 已执行"的 result
            Result result = Result.builder()
                    .transferDirection(Result.DIRECTION_UPLOAD)
                    .markStart()
                    .build();
            result.setOutcome(50, ColumnNames.STATUS_SUCCESS, "");

            orchestrator.finalize(command, config, result);

            ArgumentCaptor<Result> resultCaptor = ArgumentCaptor.forClass(Result.class);
            verify(resultRepository).insert(resultCaptor.capture());
            assertEquals(Result.DIRECTION_UPLOAD, resultCaptor.getValue().getTransferDirection());
        }

        @Test
        @DisplayName("should use DOWNLOAD direction for download scenario")
        void shouldUseDownloadDirection() {
            Command command = new Command();
            command.setId(5L);
            command.setCategoryCode("CAT");
            command.setControlCode("CTRL");

            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.DOWNLOAD_SINGLE);
            config.setDbName("DB1");
            config.setFilePath("/path");
            config.setFtpName("ftp1");
            config.setTableName("table1");

            Result result = Result.builder()
                    .transferDirection(Result.DIRECTION_DOWNLOAD)
                    .markStart()
                    .build();
            result.setOutcome(200, ColumnNames.STATUS_SUCCESS, "");

            orchestrator.finalize(command, config, result);

            ArgumentCaptor<Result> resultCaptor = ArgumentCaptor.forClass(Result.class);
            verify(resultRepository).insert(resultCaptor.capture());
            assertEquals(Result.DIRECTION_DOWNLOAD, resultCaptor.getValue().getTransferDirection());
        }
    }
}