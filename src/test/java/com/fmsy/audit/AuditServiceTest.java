package com.fmsy.audit;

import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.repository.TargetTableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditService Tests")
class AuditServiceTest {

    @Mock
    private FtpPool ftpPool;

    @Mock
    private TargetTableRepository targetTableRepository;

    @Mock
    private FtpClient ftpClient;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(ftpPool, targetTableRepository);
    }

    @Nested
    @DisplayName("preAudit method")
    class PreAuditTests {

        @Test
        @DisplayName("should return auditCount when negative")
        void shouldReturnAuditCountWhenNegative() {
            int result = auditService.preAudit(AuditScenario.UPLOAD, new Object(), -1);
            assertEquals(-1, result);
        }

        @Test
        @DisplayName("should return -1 when source is invalid for UPLOAD")
        void shouldReturnMinusOneWhenSourceInvalidForUpload() {
            int result = auditService.preAudit(AuditScenario.UPLOAD, "invalid_source", 100);
            assertEquals(-1, result);
        }

        @Test
        @DisplayName("should return actual count when UPLOAD audit passes")
        void shouldReturnActualCountWhenUploadAuditPasses() throws Exception {
            String[] source = new String[]{"ftp1", "/path/to/file.csv"};
            when(ftpPool.withClient(eq("ftp1"), any(FtpPool.FtpCallback.class))).thenAnswer(invocation -> {
                FtpPool.FtpCallback<?> callback = invocation.getArgument(1);
                return callback.run(ftpClient);
            });
            when(ftpClient.countFileLines("/path/to/file.csv")).thenReturn(100);

            int result = auditService.preAudit(AuditScenario.UPLOAD, source, 100);
            assertEquals(100, result);
        }

        @Test
        @DisplayName("should return -1 when UPLOAD audit fails")
        void shouldReturnMinusOneWhenUploadAuditFails() throws Exception {
            String[] source = new String[]{"ftp1", "/path/to/file.csv"};
            when(ftpPool.withClient(eq("ftp1"), any(FtpPool.FtpCallback.class))).thenAnswer(invocation -> {
                FtpPool.FtpCallback<?> callback = invocation.getArgument(1);
                return callback.run(ftpClient);
            });
            when(ftpClient.countFileLines("/path/to/file.csv")).thenReturn(100);

            int result = auditService.preAudit(AuditScenario.UPLOAD, source, 200);
            assertEquals(-1, result);
        }

        @Test
        @DisplayName("should return actual count when DOWNLOAD audit passes")
        void shouldReturnActualCountWhenDownloadAuditPasses() {
            when(targetTableRepository.count("DB_DEFAULT", "test_table")).thenReturn(50);

            int result = auditService.preAudit(AuditScenario.DOWNLOAD, "test_table", 50);
            assertEquals(50, result);
        }

        @Test
        @DisplayName("should return -1 when DOWNLOAD audit fails")
        void shouldReturnMinusOneWhenDownloadAuditFails() {
            when(targetTableRepository.count("DB_DEFAULT", "test_table")).thenReturn(50);

            int result = auditService.preAudit(AuditScenario.DOWNLOAD, "test_table", 100);
            assertEquals(-1, result);
        }

        @Test
        @DisplayName("should return -1 when exception occurs")
        void shouldReturnMinusOneWhenExceptionOccurs() {
            when(targetTableRepository.count(anyString(), anyString()))
                    .thenThrow(new RuntimeException("DB error"));

            int result = auditService.preAudit(AuditScenario.DOWNLOAD, "test_table", 50);
            assertEquals(-1, result);
        }
    }

    @Nested
    @DisplayName("preAuditByBucket method")
    class PreAuditByBucketTests {

        @Test
        @DisplayName("should return auditCount when negative")
        void shouldReturnAuditCountWhenNegative() {
            int result = auditService.preAuditByBucket("table", "field", "value", -1);
            assertEquals(-1, result);
        }

        @Test
        @DisplayName("should fallback to whole-table count when splitField is null")
        void shouldFallbackWhenSplitFieldIsNull() {
            when(targetTableRepository.count("DB_DEFAULT", "test_table")).thenReturn(50);

            int result = auditService.preAuditByBucket("test_table", null, "value", 50);
            assertEquals(50, result);
        }

        @Test
        @DisplayName("should fallback to whole-table count when fieldValue is empty")
        void shouldFallbackWhenFieldValueIsEmpty() {
            when(targetTableRepository.count("DB_DEFAULT", "test_table")).thenReturn(50);

            int result = auditService.preAuditByBucket("test_table", "field", "", 50);
            assertEquals(50, result);
        }

        @Test
        @DisplayName("should use bucket count when parameters are valid")
        void shouldUseBucketCountWhenParametersValid() {
            when(targetTableRepository.countByBucket("DB_DEFAULT", "test_table", "field", "value"))
                    .thenReturn(25);

            int result = auditService.preAuditByBucket("test_table", "field", "value", 25);
            assertEquals(25, result);
        }

        @Test
        @DisplayName("should return -1 when bucket count does not match")
        void shouldReturnMinusOneWhenBucketCountDoesNotMatch() {
            when(targetTableRepository.countByBucket("DB_DEFAULT", "test_table", "field", "value"))
                    .thenReturn(25);

            int result = auditService.preAuditByBucket("test_table", "field", "value", 50);
            assertEquals(-1, result);
        }
    }

    @Nested
    @DisplayName("postAudit method")
    class PostAuditTests {

        @Test
        @DisplayName("should return true when UPLOAD audit passes")
        void shouldReturnTrueWhenUploadAuditPasses() throws Exception {
            when(ftpPool.withClient(anyString(), any(FtpPool.FtpCallback.class))).thenAnswer(invocation -> {
                FtpPool.FtpCallback<?> callback = invocation.getArgument(1);
                return callback.run(ftpClient);
            });
            when(ftpClient.countFileLines("/path/to/file.csv")).thenReturn(100);
            when(targetTableRepository.count("DB_DEFAULT", "test_table")).thenReturn(100);

            boolean result = auditService.postAudit(
                    AuditScenario.UPLOAD, "ftp1",
                    new String[]{"ftp1", "/path/to/file.csv"},
                    "test_table"
            );
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when UPLOAD audit fails")
        void shouldReturnFalseWhenUploadAuditFails() throws Exception {
            when(ftpPool.withClient(anyString(), any(FtpPool.FtpCallback.class))).thenAnswer(invocation -> {
                FtpPool.FtpCallback<?> callback = invocation.getArgument(1);
                return callback.run(ftpClient);
            });
            when(ftpClient.countFileLines("/path/to/file.csv")).thenReturn(100);
            when(targetTableRepository.count("DB_DEFAULT", "test_table")).thenReturn(200);

            boolean result = auditService.postAudit(
                    AuditScenario.UPLOAD, "ftp1",
                    new String[]{"ftp1", "/path/to/file.csv"},
                    "test_table"
            );
            assertFalse(result);
        }

        @Test
        @DisplayName("should return true when DOWNLOAD audit passes with knownDbCount")
        void shouldReturnTrueWhenDownloadAuditPassesWithKnownDbCount() throws Exception {
            when(ftpPool.withClient(anyString(), any(FtpPool.FtpCallback.class))).thenAnswer(invocation -> {
                FtpPool.FtpCallback<?> callback = invocation.getArgument(1);
                return callback.run(ftpClient);
            });
            when(ftpClient.countFileLines("/path/to/file.csv")).thenReturn(100);

            boolean result = auditService.postAudit(
                    AuditScenario.DOWNLOAD, "ftp1",
                    "test_table",
                    new String[]{"ftp1", "/path/to/file.csv"},
                    100
            );
            assertTrue(result);
        }

        @Test
        @DisplayName("should query DB when knownDbCount is negative")
        void shouldQueryDbWhenKnownDbCountIsNegative() {
            when(targetTableRepository.count("DB_DEFAULT", "test_table")).thenReturn(100);

            // getFileRecordCount will fail because source is not String[] or String
            // so this returns false, but the important thing is DB was queried
            boolean result = auditService.postAudit(
                    AuditScenario.DOWNLOAD, "ftp1",
                    "test_table",
                    "invalid_source",
                    -1
            );
            assertFalse(result);
            verify(targetTableRepository).count("DB_DEFAULT", "test_table");
        }

        @Test
        @DisplayName("should return false when exception occurs")
        void shouldReturnFalseWhenExceptionOccurs() {
            when(ftpPool.withClient(anyString(), any(FtpPool.FtpCallback.class)))
                    .thenThrow(new RuntimeException("FTP error"));

            boolean result = auditService.postAudit(
                    AuditScenario.UPLOAD, "ftp1",
                    new String[]{"ftp1", "/path/to/file.csv"},
                    "test_table"
            );
            assertFalse(result);
        }
    }
}
