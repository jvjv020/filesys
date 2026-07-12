package com.fmsy.transfer.download;

import com.fmsy.audit.AuditScenario;
import com.fmsy.audit.AuditService;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.TargetTableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadSupport Tests")
class DownloadSupportTest {

    @Mock
    private AuditService auditService;

    @Mock
    private TargetTableRepository targetTableRepository;

    @Mock
    private DetailRepository detailRepository;

    @Mock
    private FtpClient ftpClient;

    private DownloadSupport downloadSupport;

    @BeforeEach
    void setUp() {
        downloadSupport = new DownloadSupport(auditService, targetTableRepository, detailRepository);
    }

    @Nested
    @DisplayName("preAudit")
    class PreAuditTests {

        @Test
        @DisplayName("should delegate to auditService.preAudit")
        void shouldDelegateToAuditService() {
            TransferConfig config = new TransferConfig();
            config.setTableName("mytable");
            config.setDbName("DB1");

            when(auditService.preAudit(AuditScenario.DOWNLOAD, "mytable", 100, "DB1"))
                    .thenReturn(95);

            int result = downloadSupport.preAudit(config, 100);

            assertEquals(95, result);
            verify(auditService).preAudit(AuditScenario.DOWNLOAD, "mytable", 100, "DB1");
        }
    }

    @Nested
    @DisplayName("preAuditByBucket")
    class PreAuditByBucketTests {

        @Test
        @DisplayName("should delegate to auditService.preAuditByBucket")
        void shouldDelegateToAuditServiceByBucket() {
            TransferConfig config = new TransferConfig();
            config.setTableName("mytable");
            config.setSplitFields("REGION");
            config.setDbName("DB1");

            when(auditService.preAuditByBucket("mytable", "REGION", "EAST", 50, "DB1"))
                    .thenReturn(50);

            int result = downloadSupport.preAuditByBucket(config, 50, "EAST");

            assertEquals(50, result);
        }
    }

    @Nested
    @DisplayName("postAudit")
    class PostAuditTests {

        @Test
        @DisplayName("should delegate to auditService.postAudit with -1")
        void shouldDelegateToAuditService() {
            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");
            config.setTableName("mytable");
            config.setDbName("DB1");

            when(auditService.postAudit(AuditScenario.DOWNLOAD, "ftp1", "mytable",
                    "/path/file.csv", -1, "DB1")).thenReturn(true);

            boolean result = downloadSupport.postAudit(config, "/path/file.csv");

            assertTrue(result);
        }

        @Test
        @DisplayName("should delegate with known db count")
        void shouldDelegateWithKnownDbCount() {
            TransferConfig config = new TransferConfig();
            config.setFtpName("ftp1");
            config.setTableName("mytable");
            config.setDbName("DB1");

            when(auditService.postAudit(AuditScenario.DOWNLOAD, "ftp1", "mytable",
                    "/path/file.csv", 100, "DB1")).thenReturn(false);

            boolean result = downloadSupport.postAudit(config, "/path/file.csv", 100);

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("countRecords")
    class CountRecordsTests {

        @Test
        @DisplayName("should delegate to targetTableRepository.count")
        void shouldDelegateToTargetTableRepository() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");

            when(targetTableRepository.count("DB1", "mytable")).thenReturn(500);

            int result = downloadSupport.countRecords(config);

            assertEquals(500, result);
        }
    }

    @Nested
    @DisplayName("checkOverwriteAllowed")
    class CheckOverwriteAllowedTests {

        @Test
        @DisplayName("should return true when overwriteFlag is null")
        void shouldReturnTrueWhenOverwriteFlagIsNull() {
            boolean result = downloadSupport.checkOverwriteAllowed(ftpClient, "/path/file.csv", null);
            assertTrue(result);
        }

        @Test
        @DisplayName("should return true when overwriteFlag is Y")
        void shouldReturnTrueWhenOverwriteFlagIsY() {
            boolean result = downloadSupport.checkOverwriteAllowed(ftpClient, "/path/file.csv", "Y");
            assertTrue(result);
        }

        @Test
        @DisplayName("should return true when file does not exist")
        void shouldReturnTrueWhenFileNotExist() {
            when(ftpClient.exists("/path/file.csv")).thenReturn(false);

            boolean result = downloadSupport.checkOverwriteAllowed(ftpClient, "/path/file.csv", "N");
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when completion flag exists")
        void shouldReturnFalseWhenCompletionFlagExists() {
            when(ftpClient.exists("/path/file.csv")).thenReturn(true);
            when(ftpClient.exists("/path/file.FLG")).thenReturn(true);

            boolean result = downloadSupport.checkOverwriteAllowed(ftpClient, "/path/file.csv", "N");
            assertFalse(result);
        }

        @Test
        @DisplayName("should check all completion flag suffixes")
        void shouldCheckAllFlagSuffixes() {
            when(ftpClient.exists("/path/file.csv")).thenReturn(true);
            when(ftpClient.exists("/path/file.FLG")).thenReturn(false);
            when(ftpClient.exists("/path/file.DONE")).thenReturn(false);
            when(ftpClient.exists("/path/file.READY")).thenReturn(false);
            when(ftpClient.exists("/path/file.OK")).thenReturn(false);

            boolean result = downloadSupport.checkOverwriteAllowed(ftpClient, "/path/file.csv", "N");
            assertTrue(result);

            verify(ftpClient).exists("/path/file.FLG");
            verify(ftpClient).exists("/path/file.DONE");
            verify(ftpClient).exists("/path/file.READY");
            verify(ftpClient).exists("/path/file.OK");
        }

        @Test
        @DisplayName("should return true when filePath is null")
        void shouldReturnTrueWhenFilePathIsNull() {
            boolean result = downloadSupport.checkOverwriteAllowed(ftpClient, null, "N");
            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("extractSubfileName")
    class ExtractSubfileNameTests {

        @Test
        @DisplayName("should extract stem from file path")
        void shouldExtractStem() {
            String result = downloadSupport.extractSubfileName("/data/file.csv");
            assertEquals("file", result);
        }

        @Test
        @DisplayName("should return full name when no extension")
        void shouldReturnFullNameWhenNoExtension() {
            String result = downloadSupport.extractSubfileName("/data/file");
            assertEquals("file", result);
        }

        @Test
        @DisplayName("should handle null input")
        void shouldHandleNull() {
            assertNull(downloadSupport.extractSubfileName(null));
        }

        @Test
        @DisplayName("should handle paths with multiple dots")
        void shouldHandleMultipleDots() {
            String result = downloadSupport.extractSubfileName("/data/my.file.name.csv");
            assertEquals("my.file.name", result);
        }
    }

    @Nested
    @DisplayName("buildSplitFieldPredicates")
    class BuildSplitFieldPredicatesTests {

        @Test
        @DisplayName("should build predicates from split fields")
        void shouldBuildPredicates() {
            List<Object> params = new java.util.ArrayList<>();

            List<String> predicates = downloadSupport.buildSplitFieldPredicates(
                    "REGION,STATUS", List.of("EAST", "ACTIVE"), params);

            assertEquals(2, predicates.size());
            assertEquals("REGION = ?", predicates.get(0));
            assertEquals("STATUS = ?", predicates.get(1));
            assertEquals(List.of("EAST", "ACTIVE"), params);
        }

        @Test
        @DisplayName("should handle mismatched field/value counts")
        void shouldHandleMismatchedCounts() {
            List<Object> params = new java.util.ArrayList<>();

            List<String> predicates = downloadSupport.buildSplitFieldPredicates(
                    "A,B,C", List.of("1", "2"), params);

            assertEquals(2, predicates.size());
            assertEquals("A = ?", predicates.get(0));
            assertEquals("B = ?", predicates.get(1));
        }

        @Test
        @DisplayName("should return empty list for null inputs")
        void shouldReturnEmptyForNullInputs() {
            List<Object> params = new java.util.ArrayList<>();

            List<String> predicates = downloadSupport.buildSplitFieldPredicates(
                    null, List.of("v"), params);
            assertTrue(predicates.isEmpty());

            predicates = downloadSupport.buildSplitFieldPredicates(
                    "F", null, params);
            assertTrue(predicates.isEmpty());
        }

        @Test
        @DisplayName("should skip empty field names")
        void shouldSkipEmptyFieldNames() {
            List<Object> params = new java.util.ArrayList<>();

            List<String> predicates = downloadSupport.buildSplitFieldPredicates(
                    ",A", List.of("v1", "v2"), params);

            assertEquals(1, predicates.size());
            assertEquals("A = ?", predicates.get(0));
        }
    }

    @Nested
    @DisplayName("determineMainStatus")
    class DetermineMainStatusTests {

        @Test
        @DisplayName("should return SUCCESS when all succeed")
        void shouldReturnSuccess() {
            assertEquals("Y", DownloadSupport.determineMainStatus(true, 0, 0));
        }

        @Test
        @DisplayName("should return ERROR when any failed")
        void shouldReturnError() {
            assertEquals("E", DownloadSupport.determineMainStatus(false, 1, 0));
        }

        @Test
        @DisplayName("should return SKIPPED when none failed but skipped")
        void shouldReturnSkipped() {
            assertEquals("N", DownloadSupport.determineMainStatus(false, 0, 1));
        }
    }

    @Nested
    @DisplayName("rollbackAfterPostAuditFailure")
    class RollbackAfterPostAuditFailureTests {

        @Test
        @DisplayName("should delete file and return true on success")
        void shouldDeleteAndReturnTrue() {
            when(ftpClient.deleteFile("/path/file.csv")).thenReturn(true);

            boolean result = DownloadSupport.rollbackAfterPostAuditFailure(
                    ftpClient, "/path/file.csv", "count mismatch");

            assertTrue(result);
            verify(ftpClient).deleteFile("/path/file.csv");
        }

        @Test
        @DisplayName("should return false when client is null")
        void shouldReturnFalseWhenClientIsNull() {
            boolean result = DownloadSupport.rollbackAfterPostAuditFailure(
                    null, "/path/file.csv", "reason");
            assertFalse(result);
        }

        @Test
        @DisplayName("should return false when filePath is null")
        void shouldReturnFalseWhenFilePathIsNull() {
            boolean result = DownloadSupport.rollbackAfterPostAuditFailure(
                    ftpClient, null, "reason");
            assertFalse(result);
        }

        @Test
        @DisplayName("should return false when file does not exist")
        void shouldReturnFalseWhenFileNotExist() {
            when(ftpClient.deleteFile("/path/file.csv")).thenReturn(false);

            boolean result = DownloadSupport.rollbackAfterPostAuditFailure(
                    ftpClient, "/path/file.csv", "reason");

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("updateDetailStatusForBucket")
    class UpdateDetailStatusForBucketTests {

        @Test
        @DisplayName("should update detail status when id is present")
        void shouldUpdateWhenIdPresent() {
            var detail = new com.fmsy.model.Detail();
            detail.setId(1L);

            downloadSupport.updateDetailStatusForBucket(detail, "Y", "node-1");

            verify(detailRepository).updateStatus(1L, "Y", "node-1");
        }

        @Test
        @DisplayName("should skip update when detail is null")
        void shouldSkipWhenDetailIsNull() {
            downloadSupport.updateDetailStatusForBucket(null, "Y", "node-1");
            verifyNoInteractions(detailRepository);
        }

        @Test
        @DisplayName("should skip update when id is null")
        void shouldSkipWhenIdIsNull() {
            var detail = new com.fmsy.model.Detail();
            detail.setId(null);

            downloadSupport.updateDetailStatusForBucket(detail, "Y", "node-1");
            verifyNoInteractions(detailRepository);
        }
    }
}
