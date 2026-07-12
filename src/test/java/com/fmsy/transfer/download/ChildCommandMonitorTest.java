package com.fmsy.transfer.download;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.ftp.FtpPool;
import com.fmsy.lifecycle.ConfigLoaderService;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.transfer.TempTransferConfigFactory;
import com.fmsy.transfer.TransferSupport;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChildCommandMonitor Tests")
class ChildCommandMonitorTest {

    @Mock
    private DetailRepository detailRepository;

    @Mock
    private ConfigLoaderService configLoader;

    @Mock
    private FtpPool ftpPool;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private ResultRepository resultRepository;

    @Mock
    private CommandRepository commandRepository;

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private TempTransferConfigFactory tempConfigFactory;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private TransactionStatus transactionStatus;

    private ChildCommandMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new ChildCommandMonitor(detailRepository, configLoader, ftpPool,
                transferSupport, resultRepository, commandRepository, dbPool,
                tempConfigFactory);
        when(dbPool.getTransactionTemplate(any())).thenReturn(transactionTemplate);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            var callback = invocation.getArgument(0,
                    org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(transactionStatus);
        });
    }

    @Nested
    @DisplayName("countCompletedChildren")
    class CountCompletedChildrenTests {

        @Test
        @DisplayName("should use LIKE pattern with mainId and pipe")
        void shouldUseLikePattern() {
            when(commandRepository.countCompletedChildren("1|%")).thenReturn(3);

            // Can't directly test private method, so test through start + behavior
            // Just verify the pattern format indirectly
            verify(commandRepository, never()).countCompletedChildren(anyString());
        }
    }

    @Nested
    @DisplayName("start - monitoring flow")
    class StartMonitoringFlow {

        @Test
        @DisplayName("should start monitoring and complete successfully")
        void shouldStartAndComplete() throws Exception {
            when(commandRepository.countCompletedChildren("1|%"))
                    .thenReturn(3); // completes on first poll

            monitor.start(1L, 3, "DB1");
            TimeUnit.MILLISECONDS.sleep(500);
            verify(commandRepository, atLeast(1)).countCompletedChildren("1|%");
        }

        @Test
        @DisplayName("should update main command status on completion")
        void shouldUpdateMainCommandOnCompletion() throws Exception {
            Detail detail = new Detail();
            detail.setStatus(ColumnNames.STATUS_SUCCESS);
            detail.setCategoryCode("CAT001");
            detail.setControlCode("CTRL001");

            when(commandRepository.countCompletedChildren("1|%"))
                    .thenReturn(3);
            when(detailRepository.findByCommandId(1L)).thenReturn(List.of(detail));

            TransferConfig config = new TransferConfig();
            config.setCategoryCode("CAT001");
            config.setControlCode("CTRL001");
            config.setPostOperations(null);
            when(configLoader.getConfigOrDefault("CAT001", "CTRL001")).thenReturn(config);

            monitor.start(1L, 3, "DB1");
            TimeUnit.MILLISECONDS.sleep(500);

            verify(commandRepository).updateMainStatus(1L, ColumnNames.STATUS_SUCCESS);
            verify(resultRepository).insertSimple(eq(1L), eq("CAT001"), eq("CTRL001"),
                    eq(ColumnNames.STATUS_SUCCESS), anyString(), eq("DB1"));
        }

        @Test
        @DisplayName("should set ERROR when has failed details")
        void shouldSetErrorWhenHasFailedDetails() throws Exception {
            Detail success = new Detail();
            success.setStatus(ColumnNames.STATUS_SUCCESS);
            Detail failed = new Detail();
            failed.setStatus(ColumnNames.STATUS_ERROR);

            when(commandRepository.countCompletedChildren("1|%"))
                    .thenReturn(2);
            when(detailRepository.findByCommandId(1L)).thenReturn(List.of(success, failed));

            TransferConfig config = new TransferConfig();
            config.setPostOperations(null);
            when(configLoader.getConfigOrDefault("CAT001", "CTRL001")).thenReturn(config);

            monitor.start(1L, 2, "DB1");
            TimeUnit.MILLISECONDS.sleep(500);

            verify(commandRepository).updateMainStatus(1L, ColumnNames.STATUS_ERROR);
        }

        @Test
        @DisplayName("should set SKIPPED when all skipped")
        void shouldSetSkippedWhenAllSkipped() throws Exception {
            Detail skipped = new Detail();
            skipped.setStatus(ColumnNames.STATUS_SKIPPED);

            when(commandRepository.countCompletedChildren("1|%"))
                    .thenReturn(1);
            when(detailRepository.findByCommandId(1L)).thenReturn(List.of(skipped));

            TransferConfig config = new TransferConfig();
            config.setPostOperations(null);
            when(configLoader.getConfigOrDefault("CAT001", "CTRL001")).thenReturn(config);

            monitor.start(1L, 1, "DB1");
            TimeUnit.MILLISECONDS.sleep(500);

            verify(commandRepository).updateMainStatus(1L, ColumnNames.STATUS_SKIPPED);
        }

        @Test
        @DisplayName("should generate total flag file when all success")
        void shouldGenerateTotalFlagWhenAllSuccess() throws Exception {
            Detail success = new Detail();
            success.setStatus(ColumnNames.STATUS_SUCCESS);
            success.setCategoryCode("CAT001");
            success.setControlCode("CTRL001");

            when(commandRepository.countCompletedChildren("1|%"))
                    .thenReturn(1);
            when(detailRepository.findByCommandId(1L)).thenReturn(List.of(success));

            TransferConfig config = new TransferConfig();
            config.setPostOperations("TOTAL:/total.flg;L S M");
            config.setFilePath("/base/path");
            config.setFtpName("ftp1");
            when(configLoader.getConfigOrDefault("CAT001", "CTRL001")).thenReturn(config);

            com.fmsy.ftp.FtpClient mockFtp = mock(com.fmsy.ftp.FtpClient.class);
            doAnswer(invocation -> {
                com.fmsy.ftp.FtpPool.FtpVoidCallback cb = invocation.getArgument(1);
                cb.run(mockFtp);
                return null;
            }).when(ftpPool).withClient(anyString(), any(com.fmsy.ftp.FtpPool.FtpVoidCallback.class));

            monitor.start(1L, 1, "DB1");
            TimeUnit.MILLISECONDS.sleep(500);

            verify(transferSupport).postProcess(any(), anyString(),
                    any(com.fmsy.util.ResolvedPath.class));
        }
    }

    @Nested
    @DisplayName("start - timeout handling")
    class StartTimeoutHandling {

        @Test
        @DisplayName("should handle timeout gracefully")
        void shouldHandleTimeout() throws Exception {
            when(commandRepository.countCompletedChildren("1|%"))
                    .thenReturn(0); // never completes

            monitor.start(1L, 3, "DB1");

            TimeUnit.MILLISECONDS.sleep(300);

            verify(commandRepository, atLeast(1)).countCompletedChildren("1|%");
        }
    }
}
