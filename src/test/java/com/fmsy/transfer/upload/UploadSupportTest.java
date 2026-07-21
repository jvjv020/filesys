package com.fmsy.transfer.upload;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.exception.FlagCheckException;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UploadSupport Tests")
class UploadSupportTest {

    @Mock
    private TargetTableRepository targetTableRepository;

    @Mock
    private DataSourceConfig.DbPool dbPool;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private FtpClient ftpClient;

    @Mock
    private FileConverter fileConverter;

    @Mock
    private ConverterFactory converterFactory;

    @Mock
    private FieldMappingBuilder fieldMappingBuilder;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private TransactionStatus transactionStatus;

    private UploadSupport uploadSupport;

    @BeforeEach
    void setUp() {
        uploadSupport = new UploadSupport(targetTableRepository, dbPool, transferSupport, converterFactory, fieldMappingBuilder);
    }

    @Nested
    @DisplayName("postAudit")
    class PostAuditTests {

        @Test
        @DisplayName("should return true when counts match")
        void shouldReturnTrueWhenCountsMatch() {
            boolean result = uploadSupport.postAudit(new TransferConfig(), 100, 100);
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when counts mismatch")
        void shouldReturnFalseWhenCountsMismatch() {
            boolean result = uploadSupport.postAudit(new TransferConfig(), 100, 95);
            assertFalse(result);
        }

        @Test
        @DisplayName("should return true when both are zero")
        void shouldReturnTrueWhenBothZero() {
            boolean result = uploadSupport.postAudit(new TransferConfig(), 0, 0);
            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("insertAndVerifyPerFileInTx")
    class InsertAndVerifyPerFileInTxTests {

        @Test
        @DisplayName("should insert records and verify in transaction")
        void shouldInsertAndVerifyInTransaction() {
            when(dbPool.getTransactionTemplate(anyString())).thenReturn(transactionTemplate);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0,
                        org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(transactionStatus);
            });
            when(transferSupport.handleEmptyData(anyInt(), any())).thenReturn(true);

            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");
            config.setEmptyDataHandling(EmptyDataHandling.ALLOW);

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Arrays.asList("col1", "col2"));

            Map<String, Object> record1 = new HashMap<>();
            record1.put("col1", "val1");
            record1.put("col2", "val2");
            List<List<Map<String, Object>>> batches = new ArrayList<>();
            batches.add(Collections.singletonList(record1));
            CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(batches.iterator());

            int count = uploadSupport.insertAndVerifyPerFileInTx(config, dataIter, mapping, null);

            assertEquals(1, count);
            verify(targetTableRepository, atLeastOnce()).batchInsert(
                    eq("DB1"), eq("mytable"), anyList(), anyList());
        }

        @Test
        @DisplayName("should return 0 for empty iterator (post-audit passes: 0==0)")
        void shouldReturnZeroForEmptyIterator() {
            when(dbPool.getTransactionTemplate(anyString())).thenReturn(transactionTemplate);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0,
                        org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(transactionStatus);
            });
            when(transferSupport.handleEmptyData(eq(0), any())).thenReturn(true);

            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");
            config.setEmptyDataHandling(EmptyDataHandling.ERROR);

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            CloseableIterator<List<Map<String, Object>>> emptyIter =
                    new CloseableIterator<>(Collections.emptyIterator());

            int count = uploadSupport.insertAndVerifyPerFileInTx(config, emptyIter, mapping, null);

            assertEquals(0, count);
        }

        @Test
        @DisplayName("should handle empty data with ALLOW mode")
        void shouldHandleEmptyDataWithAllow() {
            when(dbPool.getTransactionTemplate(anyString())).thenReturn(transactionTemplate);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0,
                        org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(transactionStatus);
            });
            when(transferSupport.handleEmptyData(eq(0), eq(EmptyDataHandling.ALLOW))).thenReturn(true);

            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");
            config.setEmptyDataHandling(EmptyDataHandling.ALLOW);

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            CloseableIterator<List<Map<String, Object>>> emptyIter =
                    new CloseableIterator<>(Collections.emptyIterator());

            int count = uploadSupport.insertAndVerifyPerFileInTx(config, emptyIter, mapping, null);

            assertEquals(0, count);
        }

        @Test
        @DisplayName("should throw on post-audit failure (always rollback)")
        void shouldThrowOnPostAuditFailure() {
            when(dbPool.getTransactionTemplate(anyString())).thenReturn(transactionTemplate);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0,
                        org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(transactionStatus);
            });
            when(transferSupport.handleEmptyData(anyInt(), any())).thenReturn(true);

            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            UploadSupport spySupport = spy(uploadSupport);
            doReturn(false).when(spySupport).postAudit(any(), anyInt(), anyInt());

            Map<String, Object> record = new HashMap<>();
            record.put("col1", "val1");
            List<List<Map<String, Object>>> batches = new ArrayList<>();
            batches.add(Collections.singletonList(record));
            CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(batches.iterator());

            assertThrows(RuntimeException.class,
                    () -> spySupport.insertAndVerifyPerFileInTx(config, dataIter, mapping, null));
        }

        @Test
        @DisplayName("should insert multiple batches when data exceeds batch size")
        void shouldInsertMultipleBatches() {
            when(dbPool.getTransactionTemplate(anyString())).thenReturn(transactionTemplate);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0,
                        org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(transactionStatus);
            });
            when(transferSupport.handleEmptyData(anyInt(), any())).thenReturn(true);

            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");
            config.setEmptyDataHandling(EmptyDataHandling.ALLOW);

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            // Generate 2500 individual records in 2500 chunks (each chunk = 1 record)
            List<List<Map<String, Object>>> allBatches = new ArrayList<>();
            for (int i = 0; i < 2500; i++) {
                Map<String, Object> record = new HashMap<>();
                record.put("col1", "val_" + i);
                allBatches.add(Collections.singletonList(record));
            }
            CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(allBatches.iterator());

            int count = uploadSupport.insertAndVerifyPerFileInTx(config, dataIter, mapping, null);

            assertEquals(2500, count);
            verify(targetTableRepository, atLeast(3)).batchInsert(
                    eq("DB1"), eq("mytable"), anyList(), anyList());
        }
    }

    @Nested
    @DisplayName("truncateTable")
    class TruncateTableTests {

        @Test
        @DisplayName("should truncate table in transaction")
        void shouldTruncateTable() {
            when(dbPool.getTransactionTemplate("DB1")).thenReturn(transactionTemplate);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0,
                        org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(transactionStatus);
            });

            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");

            uploadSupport.truncateTable(config);

            verify(targetTableRepository).truncate("DB1", "mytable");
        }
    }

    @Nested
    @DisplayName("preCheck")
    class PreCheckMethodTests {

        @Test
        @DisplayName("should return null when preCheck passes")
        void shouldReturnNullWhenPasses() {
            TransferConfig config = new TransferConfig();
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(true);

            UploadSupport.UploadResult result = uploadSupport.preCheck(ftpClient, config, fileInfo, "/data/file.csv");

            assertNull(result);
        }

        @Test
        @DisplayName("should return SKIPPED when flag file not found")
        void shouldReturnSkippedWhenFlagNotFound() {
            TransferConfig config = new TransferConfig();
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            when(transferSupport.preCheck(ftpClient, config, fileInfo)).thenReturn(false);

            UploadSupport.UploadResult result = uploadSupport.preCheck(ftpClient, config, fileInfo, "/data/file.csv");

            assertEquals(ColumnNames.STATUS_SKIPPED, result.status());
        }

        @Test
        @DisplayName("should throw FlagCheckException when FLAG content mismatches")
        void shouldThrowOnFlagCheckException() {
            TransferConfig config = new TransferConfig();
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            when(transferSupport.preCheck(ftpClient, config, fileInfo))
                    .thenThrow(new FlagCheckException("FLAG content mismatch"));

            assertThrows(FlagCheckException.class,
                    () -> uploadSupport.preCheck(ftpClient, config, fileInfo, "/data/file.csv"));
        }
    }

    @Nested
    @DisplayName("insertDataAndVerify")
    class InsertDataAndVerifyTests {

        @Test
        @DisplayName("should insert data and verify in transaction")
        void shouldInsertAndVerify() throws Exception {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");
            config.setParserType("CSV");
            config.setEmptyDataHandling(EmptyDataHandling.ALLOW);

            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            String filePath = "/data/file.csv";

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            when(fieldMappingBuilder.buildForUpload(config, null)).thenReturn(mapping);
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(ftpClient.getInputStream(filePath)).thenReturn(mock(InputStream.class));

            Map<String, Object> record = new HashMap<>();
            record.put("col1", "val1");
            List<List<Map<String, Object>>> batches = new ArrayList<>();
            batches.add(Collections.singletonList(record));
            CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(batches.iterator());

            when(fileConverter.parse(any(InputStream.class), eq(mapping))).thenReturn(dataIter);

            // Spy to mock insertAndVerifyPerFileInTx since it needs transaction template
            UploadSupport spySupport = spy(uploadSupport);
            doReturn(1).when(spySupport).insertAndVerifyPerFileInTx(eq(config), any(), eq(mapping), any());

            int count = spySupport.insertDataAndVerify(ftpClient, config, fileInfo, null, filePath, null);

            assertEquals(1, count);
            verify(fieldMappingBuilder).buildForUpload(config, null);
        }

        @Test
        @DisplayName("should throw RuntimeException on insert failure")
        void shouldThrowOnInsertFailure() throws Exception {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");
            config.setParserType("CSV");

            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            String filePath = "/data/file.csv";

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            when(fieldMappingBuilder.buildForUpload(config, null)).thenReturn(mapping);
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(ftpClient.getInputStream(filePath)).thenReturn(mock(InputStream.class));
            when(fileConverter.parse(any(InputStream.class), eq(mapping)))
                    .thenThrow(new RuntimeException("Parse error"));

            assertThrows(RuntimeException.class,
                    () -> uploadSupport.insertDataAndVerify(ftpClient, config, fileInfo, null, filePath, null));
        }
    }

    @Nested
    @DisplayName("postProcess")
    class PostProcessMethodTests {

        @Test
        @DisplayName("should delegate to transferSupport.postProcess")
        void shouldDelegateToTransferSupport() {
            TransferConfig config = new TransferConfig();
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");

            uploadSupport.postProcess(ftpClient, config, fileInfo, 100);

            verify(transferSupport).postProcess(eq(ftpClient), eq(config), eq(fileInfo), anyMap());
        }

        @Test
        @DisplayName("should not throw when postProcess fails")
        @SuppressWarnings("unchecked")
        void shouldNotThrowOnFailure() {
            TransferConfig config = new TransferConfig();
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            doThrow(new RuntimeException("Post-process error"))
                    .when(transferSupport).postProcess(any(com.fmsy.ftp.FtpClient.class), any(TransferConfig.class), any(ResolvedPath.class), anyMap());

            // Should not throw
            assertDoesNotThrow(() ->
                    uploadSupport.postProcess(ftpClient, config, fileInfo, 100));
        }
    }

    @Nested
    @DisplayName("moveDataAndFlagToErrorDir")
    class MoveDataAndFlagToErrorDirTests {

        @Test
        @DisplayName("should move data file and flag file to error dir")
        void shouldMoveDataAndFlagFile() {
            TransferConfig config = new TransferConfig();
            config.setPreOperations("FLAG:{stem}.OK");
            when(ftpClient.exists("/data/file.OK")).thenReturn(true);
            when(ftpClient.moveToErrorDir("/data/file.csv")).thenReturn("/error/file.csv");
            when(ftpClient.moveToErrorDir("/data/file.OK")).thenReturn("/error/file.OK");

            uploadSupport.moveDataAndFlagToErrorDir(ftpClient, "/data/file.csv", config);

            verify(ftpClient).moveToErrorDir("/data/file.csv");
            verify(ftpClient).moveToErrorDir("/data/file.OK");
        }

        @Test
        @DisplayName("should handle move failure gracefully")
        void shouldHandleMoveFailure() {
            TransferConfig config = new TransferConfig();
            config.setPreOperations("FLAG:{stem}.OK");
            when(ftpClient.moveToErrorDir("/data/file.csv")).thenThrow(new RuntimeException("Move failed"));

            // Should not throw
            assertDoesNotThrow(() ->
                    uploadSupport.moveDataAndFlagToErrorDir(ftpClient, "/data/file.csv", config));
        }
    }

    @Nested
    @DisplayName("determineMainStatus")
    class DetermineMainStatusTests {

        @Test
        @DisplayName("should return SUCCESS when all succeed")
        void shouldReturnSuccessWhenAllSucceed() {
            assertEquals(ColumnNames.STATUS_SUCCESS,
                    TransferSupport.determineMainStatus(true, 0, 0));
        }

        @Test
        @DisplayName("should return ERROR when any failed")
        void shouldReturnErrorWhenAnyFailed() {
            assertEquals(ColumnNames.STATUS_ERROR,
                    TransferSupport.determineMainStatus(false, 2, 0));
        }

        @Test
        @DisplayName("should return SKIPPED when all skipped")
        void shouldReturnSkippedWhenAllSkipped() {
            assertEquals(ColumnNames.STATUS_SKIPPED,
                    TransferSupport.determineMainStatus(false, 0, 1));
        }
    }

    @Nested
    @DisplayName("UploadResult")
    class UploadResultTests {

        @Test
        @DisplayName("allSkipped 返回 SKIPPED 状态")
        void allSkippedShouldHaveSkippedStatus() {
            var result = UploadSupport.UploadResult.allSkipped();
            assertEquals(0, result.records());
            assertEquals(ColumnNames.STATUS_SKIPPED, result.status());
        }
    }

    @Nested
    @DisplayName("resolveConfiguredFlagPath")
    class ResolveConfiguredFlagPathTests {

        @Test
        @DisplayName("should resolve FLAG with stem pattern")
        void shouldResolveFlagStemPattern() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/export/file.csv");
            String result = UploadSupport.resolveConfiguredFlagPath("FLAG:{stem}.OK", fileInfo);
            assertEquals("/data/export/file.OK", result);
        }

        @Test
        @DisplayName("should resolve FLAG with mode suffix")
        void shouldResolveFlagWithMode() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            String result = UploadSupport.resolveConfiguredFlagPath("FLAG:{stem}.OK;L", fileInfo);
            assertEquals("/data/file.OK", result);
        }

        @Test
        @DisplayName("should resolve READY pattern")
        void shouldResolveReadyPattern() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            String result = UploadSupport.resolveConfiguredFlagPath("READY:{stem}.ready", fileInfo);
            assertEquals("/data/file.ready", result);
        }

        @Test
        @DisplayName("should return null when no FLAG/READY in ops")
        void shouldReturnNullWhenNoFlagOrReady() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            assertNull(UploadSupport.resolveConfiguredFlagPath("DEL:old.csv", fileInfo));
            assertNull(UploadSupport.resolveConfiguredFlagPath("", fileInfo));
            assertNull(UploadSupport.resolveConfiguredFlagPath(null, fileInfo));
        }

        @Test
        @DisplayName("should handle name pattern")
        void shouldResolveNamePattern() {
            ResolvedPath fileInfo = ResolvedPath.of("/data/file.csv");
            String result = UploadSupport.resolveConfiguredFlagPath("FLAG:{name}.flag", fileInfo);
            assertEquals("/data/file.csv.flag", result);
        }
    }

}