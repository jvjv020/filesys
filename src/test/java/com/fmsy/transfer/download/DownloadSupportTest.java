package com.fmsy.transfer.download;

import com.fmsy.audit.AuditScenario;
import com.fmsy.audit.AuditService;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferSupport;
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
    private FtpPool ftpPool;

    @Mock
    private TransferSupport transferSupport;

    @Mock
    private ConverterFactory converterFactory;

    @Mock
    private FieldMappingBuilder fieldMappingBuilder;

    @Mock
    private ParallelFileGenerator parallelFileGenerator;

    @Mock
    private FtpClient ftpClient;

    private DownloadSupport downloadSupport;

    @BeforeEach
    void setUp() {
        downloadSupport = new DownloadSupport(auditService, targetTableRepository, detailRepository,
                ftpPool, transferSupport, converterFactory, fieldMappingBuilder, parallelFileGenerator);
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
    @DisplayName("determineMainStatus")
    class DetermineMainStatusTests {

        @Test
        @DisplayName("should return SUCCESS when all succeed")
        void shouldReturnSuccess() {
            assertEquals("Y", TransferSupport.determineMainStatus(true, 0, 0));
        }

        @Test
        @DisplayName("should return ERROR when any failed")
        void shouldReturnError() {
            assertEquals("E", TransferSupport.determineMainStatus(false, 1, 0));
        }

        @Test
        @DisplayName("should return SKIPPED when none failed but skipped")
        void shouldReturnSkipped() {
            assertEquals("N", TransferSupport.determineMainStatus(false, 0, 1));
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
}
