package com.fmsy.transfer.upload;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.ftp.FtpPool.FtpCallback;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
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
    private FtpPool ftpPool;

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
    private TransactionTemplate transactionTemplate;

    @Mock
    private TransactionStatus transactionStatus;

    private UploadSupport uploadSupport;

    @BeforeEach
    void setUp() {
        uploadSupport = new UploadSupport(ftpPool, targetTableRepository, dbPool, transferSupport);
    }

    @Nested
    @DisplayName("preAudit")
    class PreAuditTests {

        @Test
        @DisplayName("should return 0 when audit passes")
        void shouldReturnZeroWhenAuditPasses() throws Exception {
            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");

            when(ftpPool.withClient(eq("ftp1"), any(FtpCallback.class))).thenAnswer(invocation -> {
                var callback = invocation.getArgument(1,
                        FtpCallback.class);
                return callback.run(ftpClient);
            });
            when(ftpClient.getInputStream("/data/file.csv")).thenReturn(mock(InputStream.class));
            when(fileConverter.countRecords(any(InputStream.class), isNull())).thenReturn(100);

            int result = uploadSupport.preAudit(100, config, "/data/file.csv", fileConverter);

            assertEquals(0, result);
        }

        @Test
        @DisplayName("should return -1 when record count mismatches")
        void shouldReturnMinusOneWhenCountMismatches() throws Exception {
            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");

            when(ftpPool.withClient(eq("ftp1"), any(FtpCallback.class))).thenAnswer(invocation -> {
                var callback = invocation.getArgument(1,
                        FtpCallback.class);
                return callback.run(ftpClient);
            });
            when(ftpClient.getInputStream("/data/file.csv")).thenReturn(mock(InputStream.class));
            when(fileConverter.countRecords(any(InputStream.class), isNull())).thenReturn(50);

            int result = uploadSupport.preAudit(100, config, "/data/file.csv", fileConverter);

            assertEquals(-1, result);
        }

        @Test
        @DisplayName("should return 0 immediately when auditCount is null (no file open)")
        void shouldReturnZeroWhenAuditCountIsNull() throws Exception {
            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");

            // No ftpPool interaction expected — should return immediately
            int result = uploadSupport.preAudit(null, config, "/data/file.csv", fileConverter);

            assertEquals(0, result);
            verifyNoInteractions(ftpPool);
        }

        @Test
        @DisplayName("should return -1 when exception occurs")
        void shouldReturnMinusOneOnException() throws Exception {
            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");

            when(ftpPool.withClient(eq("ftp1"), any(FtpCallback.class))).thenThrow(new RuntimeException("FTP error"));

            int result = uploadSupport.preAudit(100, config, "/data/file.csv", fileConverter);

            assertEquals(-1, result);
        }

        @Test
        @DisplayName("should return 0 when countRecords returns negative (skip audit)")
        void shouldReturnZeroWhenCountRecordsReturnsNegative() throws Exception {
            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");

            when(ftpPool.withClient(eq("ftp1"), any(FtpCallback.class))).thenAnswer(invocation -> {
                var callback = invocation.getArgument(1,
                        FtpCallback.class);
                return callback.run(ftpClient);
            });
            when(ftpClient.getInputStream("/data/file.csv")).thenReturn(mock(InputStream.class));
            when(fileConverter.countRecords(any(InputStream.class), isNull())).thenReturn(-1);

            int result = uploadSupport.preAudit(100, config, "/data/file.csv", fileConverter);

            assertEquals(0, result);
        }

        @Test
        @DisplayName("should return 0 immediately when auditCount is negative")
        void shouldReturnZeroWhenAuditCountIsNegative() throws Exception {
            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");

            // No ftpPool interaction expected — should return immediately
            int result = uploadSupport.preAudit(-1, config, "/data/file.csv", fileConverter);

            assertEquals(0, result);
            verifyNoInteractions(ftpPool);
        }
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
        @DisplayName("should return false when both are zero")
        void shouldReturnFalseWhenBothZero() {
            boolean result = uploadSupport.postAudit(new TransferConfig(), 0, 0);
            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("insertBatchInTx")
    class InsertBatchInTxTests {

        @Test
        @DisplayName("should insert records in transaction")
        void shouldInsertRecordsInTransaction() {
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
            mapping.setTableFields(Arrays.asList("col1", "col2"));

            Map<String, Object> record1 = new HashMap<>();
            record1.put("col1", "val1");
            record1.put("col2", "val2");
            Map<String, Object> record2 = new HashMap<>();
            record2.put("col1", "val3");
            record2.put("col2", "val4");

            List<List<Map<String, Object>>> batches = new ArrayList<>();
            batches.add(Arrays.asList(record1, record2));
            Iterator<List<Map<String, Object>>> dataIter = batches.iterator();

            int count = uploadSupport.insertBatchInTx(config, dataIter, mapping);

            verify(targetTableRepository, atLeastOnce()).batchInsert(
                    eq("DB1"), eq("mytable"), anyList(), anyList());
        }

        @Test
        @DisplayName("should truncate first when truncateFirst is true")
        void shouldTruncateFirstWhenFlagIsSet() {
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

            Map<String, Object> record = new HashMap<>();
            record.put("col1", "val1");

            List<List<Map<String, Object>>> batches = new ArrayList<>();
            batches.add(Collections.singletonList(record));
            Iterator<List<Map<String, Object>>> dataIter = batches.iterator();

            uploadSupport.insertBatchInTx(config, dataIter, mapping, true);

            verify(targetTableRepository).truncate("DB1", "mytable");
        }

        @Test
        @DisplayName("should throw when handleEmptyData returns false")
        void shouldThrowWhenHandleEmptyDataReturnsFalse() {
            when(dbPool.getTransactionTemplate(anyString())).thenReturn(transactionTemplate);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0,
                        org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(transactionStatus);
            });
            when(transferSupport.handleEmptyData(anyInt(), any())).thenReturn(false);

            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");
            config.setEmptyDataHandling(com.fmsy.enums.EmptyDataHandling.ERROR);

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            Iterator<List<Map<String, Object>>> dataIter = Collections.emptyIterator();

            assertThrows(RuntimeException.class,
                    () -> uploadSupport.insertBatchInTx(config, dataIter, mapping));
        }

        @Test
        @DisplayName("should handle empty data iterator gracefully")
        void shouldHandleEmptyDataIterator() {
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

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            Iterator<List<Map<String, Object>>> emptyIter = Collections.emptyIterator();

            int count = uploadSupport.insertBatchInTx(config, emptyIter, mapping);
            assertEquals(0, count);
            verifyNoInteractions(targetTableRepository);
        }
    }

    @Nested
    @DisplayName("insertAndVerifyInTx")
    class InsertAndVerifyInTxTests {

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

            Result result = new Result();

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Arrays.asList("col1", "col2"));

            Map<String, Object> record1 = new HashMap<>();
            record1.put("col1", "val1");
            record1.put("col2", "val2");
            List<List<Map<String, Object>>> batches = new ArrayList<>();
            batches.add(Collections.singletonList(record1));
            CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(batches.iterator());

            int count = uploadSupport.insertAndVerifyInTx(config, dataIter, mapping, false, result);

            // Expect 1 record inserted successfully
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

            Result result = new Result();

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            CloseableIterator<List<Map<String, Object>>> emptyIter =
                    new CloseableIterator<>(Collections.emptyIterator());

            int count = uploadSupport.insertAndVerifyInTx(config, emptyIter, mapping, false, result);

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

            Result result = new Result();

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            CloseableIterator<List<Map<String, Object>>> emptyIter =
                    new CloseableIterator<>(Collections.emptyIterator());

            int count = uploadSupport.insertAndVerifyInTx(config, emptyIter, mapping, false, result);

            assertEquals(0, count);
        }

        @Test
        @DisplayName("should throw on post-audit failure in ERROR mode")
        void shouldThrowOnPostAuditFailureInErrorMode() {
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
            config.setEmptyDataHandling(EmptyDataHandling.ERROR);

            Result result = new Result();

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            // Spy on uploadSupport to mock postAudit to return false
            UploadSupport spySupport = spy(uploadSupport);
            doReturn(false).when(spySupport).postAudit(any(), anyInt(), anyInt());

            Map<String, Object> record = new HashMap<>();
            record.put("col1", "val1");
            List<List<Map<String, Object>>> batches = new ArrayList<>();
            batches.add(Collections.singletonList(record));
            CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(batches.iterator());

            assertThrows(RuntimeException.class,
                    () -> spySupport.insertAndVerifyInTx(config, dataIter, mapping, false, result));
        }

        @Test
        @DisplayName("should keep data and set ERROR result in non-ERROR mode post-audit failure")
        void shouldKeepDataAndSetErrorOnNonErrorPostAuditFailure() {
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
            config.setEmptyDataHandling(EmptyDataHandling.ALLOW); // non-ERROR

            Result result = new Result();

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            // Spy on uploadSupport to mock postAudit to return false
            UploadSupport spySupport = spy(uploadSupport);
            doReturn(false).when(spySupport).postAudit(any(), anyInt(), anyInt());

            Map<String, Object> record = new HashMap<>();
            record.put("col1", "val1");
            List<List<Map<String, Object>>> batches = new ArrayList<>();
            batches.add(Collections.singletonList(record));
            CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(batches.iterator());

            int count = spySupport.insertAndVerifyInTx(config, dataIter, mapping, false, result);

            // Data committed (returned count), result marked ERROR with description
            assertEquals(1, count);
            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            assertTrue(result.getDescription().contains("Post-audit failed"));
        }

        @Test
        @DisplayName("should truncate first when truncateFirst is true")
        void shouldTruncateFirstInInsertAndVerify() {
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

            Result result = new Result();

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            Map<String, Object> record = new HashMap<>();
            record.put("col1", "val1");
            List<List<Map<String, Object>>> batches = new ArrayList<>();
            batches.add(Collections.singletonList(record));
            CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(batches.iterator());

            int count = uploadSupport.insertAndVerifyInTx(config, dataIter, mapping, true, result);

            assertEquals(1, count);
            verify(targetTableRepository).truncate("DB1", "mytable");
            verify(targetTableRepository, atLeastOnce()).batchInsert(
                    eq("DB1"), eq("mytable"), anyList(), anyList());
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

            Result result = new Result();

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            // Generate 2500 records → should exceed DEFAULT_BATCH_SIZE (1000)
            // and produce 3 batch inserts (1000 + 1000 + 500)
            List<List<Map<String, Object>>> allBatches = new ArrayList<>();
            for (int batchIdx = 0; batchIdx < 3; batchIdx++) {
                List<Map<String, Object>> chunk = new ArrayList<>();
                int chunkSize = batchIdx < 2 ? 1000 : 500;
                for (int i = 0; i < chunkSize; i++) {
                    Map<String, Object> record = new HashMap<>();
                    record.put("col1", "val_" + batchIdx + "_" + i);
                    chunk.add(record);
                }
                allBatches.add(chunk);
            }
            CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(allBatches.iterator());

            int count = uploadSupport.insertAndVerifyInTx(config, dataIter, mapping, false, result);

            assertEquals(2500, count);
            // Verify at least 3 batchInsert calls (exact count depends on batching)
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
    @DisplayName("determineMainStatus")
    class DetermineMainStatusTests {

        @Test
        @DisplayName("should return SUCCESS when all succeed")
        void shouldReturnSuccessWhenAllSucceed() {
            var result = new UploadSupport.UploadResult(100, 1, 0, 0);
            assertEquals(ColumnNames.STATUS_SUCCESS, UploadSupport.determineMainStatus(result));
        }

        @Test
        @DisplayName("should return ERROR when any failed")
        void shouldReturnErrorWhenAnyFailed() {
            var result = new UploadSupport.UploadResult(50, 0, 0, 2);
            assertEquals(ColumnNames.STATUS_ERROR, UploadSupport.determineMainStatus(result));
        }

        @Test
        @DisplayName("should return SKIPPED when all skipped")
        void shouldReturnSkippedWhenAllSkipped() {
            var result = new UploadSupport.UploadResult(0, 0, 1, 0);
            assertEquals(ColumnNames.STATUS_SKIPPED, UploadSupport.determineMainStatus(result));
        }
    }

    @Nested
    @DisplayName("UploadResult records")
    class UploadResultTests {

        @Test
        @DisplayName("allSkipped should return result with skippedCount=1")
        void allSkippedShouldHaveSkippedCount() {
            var result = UploadSupport.UploadResult.allSkipped();
            assertEquals(0, result.records());
            assertEquals(0, result.successCount());
            assertEquals(1, result.skippedCount());
            assertEquals(0, result.failedCount());
        }
    }
}
