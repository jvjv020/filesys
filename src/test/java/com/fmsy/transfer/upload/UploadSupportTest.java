package com.fmsy.transfer.upload;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.exception.FlagCheckException;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.ftp.FtpPool.FtpCallback;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
        uploadSupport = new UploadSupport(ftpPool, targetTableRepository, dbPool, transferSupport, converterFactory, fieldMappingBuilder);
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

            int count = uploadSupport.insertAndVerifyPerFileInTx(config, dataIter, mapping, false);

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

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            CloseableIterator<List<Map<String, Object>>> emptyIter =
                    new CloseableIterator<>(Collections.emptyIterator());

            int count = uploadSupport.insertAndVerifyPerFileInTx(config, emptyIter, mapping, false);

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

            int count = uploadSupport.insertAndVerifyPerFileInTx(config, emptyIter, mapping, false);

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
                    () -> spySupport.insertAndVerifyPerFileInTx(config, dataIter, mapping, false));
        }

        @Test
        @DisplayName("should return -1 on non-ERROR mode post-audit failure")
        void shouldReturnMinusOneOnNonErrorPostAuditFailure() {
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

            int count = spySupport.insertAndVerifyPerFileInTx(config, dataIter, mapping, false);

            // Data committed (data kept), but returns -1 to signal audit failure
            assertEquals(-1, count);
        }

        @Test
        @DisplayName("should truncate first when truncateFirst is true")
        void shouldTruncateFirst() {
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

            Map<String, Object> record = new HashMap<>();
            record.put("col1", "val1");
            List<List<Map<String, Object>>> batches = new ArrayList<>();
            batches.add(Collections.singletonList(record));
            CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(batches.iterator());

            int count = uploadSupport.insertAndVerifyPerFileInTx(config, dataIter, mapping, true);

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

            FieldMapping mapping = new FieldMapping();
            mapping.setTableFields(Collections.singletonList("col1"));

            // Generate 2500 individual records in 2500 chunks (each chunk = 1 record)
            // so CloseableIterator.getRecordCount() matches totalRecords for post-audit
            List<List<Map<String, Object>>> allBatches = new ArrayList<>();
            for (int i = 0; i < 2500; i++) {
                Map<String, Object> record = new HashMap<>();
                record.put("col1", "val_" + i);
                allBatches.add(Collections.singletonList(record));
            }
            CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(allBatches.iterator());

            int count = uploadSupport.insertAndVerifyPerFileInTx(config, dataIter, mapping, false);

            assertEquals(2500, count);
            // Should produce 3 batchInsert calls (1000 + 1000 + 500)
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
            var result = new UploadSupport.UploadResult(100, 1, 0, 0, null);
            assertEquals(ColumnNames.STATUS_SUCCESS, UploadSupport.determineMainStatus(result));
        }

        @Test
        @DisplayName("should return ERROR when any failed")
        void shouldReturnErrorWhenAnyFailed() {
            var result = new UploadSupport.UploadResult(50, 0, 0, 2, ColumnNames.STATUS_ERROR);
            assertEquals(ColumnNames.STATUS_ERROR, UploadSupport.determineMainStatus(result));
        }

        @Test
        @DisplayName("should return SKIPPED when all skipped")
        void shouldReturnSkippedWhenAllSkipped() {
            var result = new UploadSupport.UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED);
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

    @Nested
    @DisplayName("processSingleFile")
    class ProcessSingleFileTests {

        @Test
        @DisplayName("should return success when uploadSingleFile succeeds")
        void shouldReturnSuccess() throws Exception {
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });

            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");

            UploadSupport spySupport = spy(uploadSupport);
            doReturn(new UploadSupport.UploadResult(100, 1, 0, 0, null))
                    .when(spySupport).uploadSingleFile(any(), any(), any(), anyString(), any());

            UploadSupport.UploadResult result = spySupport.processSingleFile(
                    "ftp1", 100, config, "/data/file.csv", null);

            assertNull(result.status());
            assertEquals(100, result.records());
        }

        @Test
        @DisplayName("should return skipped when uploadSingleFile returns skipped")
        void shouldReturnSkipped() throws Exception {
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });

            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");

            UploadSupport spySupport = spy(uploadSupport);
            doReturn(new UploadSupport.UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED))
                    .when(spySupport).uploadSingleFile(any(), any(), any(), anyString(), any());

            UploadSupport.UploadResult result = spySupport.processSingleFile(
                    "ftp1", 100, config, "/data/file.csv", null);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.status());
            verify(ftpClient, never()).moveToErrorDir(anyString());
        }

        @Test
        @DisplayName("should catch FlagCheckException and return ERROR with file move")
        void shouldHandleFlagCheckException() throws Exception {
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(ftpClient.exists(anyString())).thenReturn(false);
            when(ftpClient.moveToErrorDir(anyString())).thenReturn("/error/file.csv");

            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");
            config.setPreOperations("FLAG:{stem}.OK");

            UploadSupport spySupport = spy(uploadSupport);
            doThrow(new FlagCheckException("FLAG check failed"))
                    .when(spySupport).uploadSingleFile(any(), any(), any(), anyString(), any());

            UploadSupport.UploadResult result = spySupport.processSingleFile(
                    "ftp1", 100, config, "/data/file.csv", null);

            assertEquals(ColumnNames.STATUS_ERROR, result.status());
            verify(ftpClient, atLeastOnce()).moveToErrorDir(anyString());
        }

        @Test
        @DisplayName("should catch RuntimeException and return ERROR with file move")
        void shouldHandleRuntimeException() throws Exception {
            when(transferSupport.executeWithClient(eq("ftp1"), any()))
                    .thenAnswer(invocation -> {
                        TransferSupport.FtpClientCallback<?> cb = invocation.getArgument(1);
                        return cb.run(ftpClient);
                    });
            when(ftpClient.moveToErrorDir(anyString())).thenReturn("/error/file.csv");

            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");

            UploadSupport spySupport = spy(uploadSupport);
            doThrow(new RuntimeException("Pre-audit failed"))
                    .when(spySupport).uploadSingleFile(any(), any(), any(), anyString(), any());

            UploadSupport.UploadResult result = spySupport.processSingleFile(
                    "ftp1", 100, config, "/data/file.csv", null);

            assertEquals(ColumnNames.STATUS_ERROR, result.status());
            verify(ftpClient, atLeastOnce()).moveToErrorDir(anyString());
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

    @Nested
    @DisplayName("normalizePathSlashes")
    class NormalizePathSlashesTests {

        @Test
        @DisplayName("should normalize simple parent references")
        void shouldNormalizeSimpleParents() {
            assertEquals("/base/file.OK",
                    UploadSupport.normalizePathSlashes("/base/sub/../file.OK"));
        }

        @Test
        @DisplayName("should return path unchanged when no parent refs")
        void shouldReturnUnchangedWhenNoParents() {
            assertEquals("/data/export/file.OK",
                    UploadSupport.normalizePathSlashes("/data/export/file.OK"));
        }

        @Test
        @DisplayName("should handle null")
        void shouldHandleNull() {
            assertNull(UploadSupport.normalizePathSlashes(null));
        }
    }
}
