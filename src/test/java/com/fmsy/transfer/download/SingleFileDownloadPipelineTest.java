package com.fmsy.transfer.download;

import com.fmsy.audit.AuditScenario;
import com.fmsy.audit.AuditService;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Detail;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.OutputStream;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DownloadSupport Pipeline Tests")
class SingleFileDownloadPipelineTest {

    @Mock private AuditService auditService;
    @Mock private TargetTableRepository targetTableRepository;
    @Mock private DetailRepository detailRepository;
    @Mock private FtpPool ftpPool;
    @Mock private TransferSupport transferSupport;
    @Mock private ConverterFactory converterFactory;
    @Mock private FieldMappingBuilder fieldMappingBuilder;
    @Mock private ParallelFileGenerator parallelFileGenerator;
    @Mock private FtpClient ftpClient;
    @Mock private FileConverter fileConverter;

    private DownloadSupport pipeline;
    private TransferConfig config;
    private ResolvedPath targetFile;
    private static final String FTP = "ftp1";
    private static final String PATH = "/data/out.csv";

    @BeforeEach
    void setUp() {
        pipeline = new DownloadSupport(auditService, targetTableRepository, detailRepository,
                ftpPool, transferSupport, converterFactory, fieldMappingBuilder, parallelFileGenerator);
        config = new TransferConfig();
        config.setFtpName(FTP);
        config.setFilePath(PATH);
        config.setParserType("CSV");
        config.setTableName("mytable");
        config.setDbName("DB1");
        config.setSplitFields("REGION");
        config.setOverwriteFlag("Y");
        targetFile = ResolvedPath.of(PATH);
    }

    private void mockPreAudit(int count) {
        when(auditService.preAudit(AuditScenario.DOWNLOAD, "mytable", count, "DB1")).thenReturn(count);
    }
    private void mockEmptyOk(int rc) {
        when(transferSupport.handleEmptyData(rc, config.getEmptyDataHandling())).thenReturn(true);
    }
    private void mockFtp() {
        when(ftpPool.getClient(FTP)).thenReturn(ftpClient);
    }
    private void mockWholeTableGen(int count) throws Exception {
        FieldMapping m = new FieldMapping();
        m.setTableFields(Collections.singletonList("col1"));
        when(fieldMappingBuilder.buildForDownload(config)).thenReturn(m);
        when(converterFactory.get("CSV")).thenReturn(fileConverter);
        when(ftpClient.getOutputStream(PATH)).thenReturn(mock(OutputStream.class));
        when(parallelFileGenerator.generate(any(OutputStream.class), eq(config),
                eq(fileConverter), eq(m), eq((long) count))).thenReturn(count);
    }
    private void mockPostAuditOk(int count) {
        when(auditService.postAudit(AuditScenario.DOWNLOAD, FTP, "mytable", PATH, count, "DB1")).thenReturn(true);
    }
    private void mockPreCheckOk() {
        when(transferSupport.preCheck(ftpClient, config, targetFile)).thenReturn(true);
    }

    // ==================== Whole table mode ====================

    @Nested
    @DisplayName("Whole table")
    class WholeTable {

        private DownloadSupport.PipelineOptions.PipelineOptionsBuilder opts() {
            return DownloadSupport.PipelineOptions.builder()
                    .wholeTable(true).expectedAuditCount(100)
                    .enablePreCheck(true).enableOverwriteCheck(true).enablePostAudit(true);
        }

        @Test
        @DisplayName("full pipeline success")
        void success() throws Exception {
            config.setPostOperations("SUB:/s.flg;L");
            mockPreAudit(100);
            mockEmptyOk(100);
            mockFtp();
            mockWholeTableGen(100);
            mockPreCheckOk();
            mockPostAuditOk(100);
            // overwriteFlag=Y -> checkOverwriteAllowed returns true without client calls

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile, opts().build());

            assertTrue(r.isSuccess());
            assertEquals(100, r.getRecordCount());
            assertEquals(ColumnNames.STATUS_SUCCESS, r.getStatus());
            verify(transferSupport).postProcess(eq(ftpClient), anyString(), eq(targetFile), eq(100));
            verify(ftpClient).completePendingCommand();
        }

        @Test
        @DisplayName("preCheck fail -> SKIPPED")
        void preCheckFail() throws Exception {
            mockPreAudit(100);
            mockEmptyOk(100);
            mockFtp();
            when(transferSupport.preCheck(ftpClient, config, targetFile)).thenReturn(false);

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile, opts().build());
            assertEquals(ColumnNames.STATUS_SKIPPED, r.getStatus());
        }

        @Test
        @DisplayName("overwrite denied -> ERROR")
        void overwriteDenied() throws Exception {
            config.setOverwriteFlag("N");
            mockPreAudit(100);
            mockEmptyOk(100);
            mockFtp();
            mockPreCheckOk();
            when(ftpClient.exists(PATH)).thenReturn(true);
            when(ftpClient.exists(PATH.replace(".csv", ".FLG"))).thenReturn(true);

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile, opts().build());
            assertEquals(ColumnNames.STATUS_ERROR, r.getStatus());
        }

        @Test
        @DisplayName("preAudit fail -> SKIPPED")
        void preAuditFail() {
            when(auditService.preAudit(AuditScenario.DOWNLOAD, "mytable", 100, "DB1")).thenReturn(-1);

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile, opts().build());
            assertEquals(ColumnNames.STATUS_SKIPPED, r.getStatus());
        }

        @Test
        @DisplayName("null auditCount -> countRecords fallback")
        void nullAuditCount() throws Exception {
            config.setPostOperations("FB:/f.flg;S");
            when(targetTableRepository.count("DB1", "mytable")).thenReturn(50);
            mockEmptyOk(50);
            mockFtp();
            mockWholeTableGen(50);
            mockPreCheckOk();
            mockPostAuditOk(50);

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile,
                    opts().expectedAuditCount(null).build());

            assertTrue(r.isSuccess());
            assertEquals(50, r.getRecordCount());
            verify(targetTableRepository).count("DB1", "mytable");
        }

        @Test
        @DisplayName("empty data SKIP")
        void emptySkip() {
            config.setEmptyDataHandling(EmptyDataHandling.SKIP);
            when(auditService.preAudit(AuditScenario.DOWNLOAD, "mytable", 100, "DB1")).thenReturn(0);
            when(transferSupport.handleEmptyData(0, EmptyDataHandling.SKIP)).thenReturn(false);

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile, opts().build());
            assertEquals(ColumnNames.STATUS_SKIPPED, r.getStatus());
        }

        @Test
        @DisplayName("empty data ERROR")
        void emptyError() {
            config.setEmptyDataHandling(EmptyDataHandling.ERROR);
            when(auditService.preAudit(AuditScenario.DOWNLOAD, "mytable", 100, "DB1")).thenReturn(0);
            when(transferSupport.handleEmptyData(0, EmptyDataHandling.ERROR)).thenReturn(false);

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile, opts().build());
            assertEquals(ColumnNames.STATUS_ERROR, r.getStatus());
        }

        @Test
        @DisplayName("postAudit fail -> rollback + ERROR")
        void postAuditFail() throws Exception {
            config.setPostOperations("FB:/f.flg;S");
            mockPreAudit(100);
            mockEmptyOk(100);
            mockFtp();
            mockWholeTableGen(100);
            mockPreCheckOk();
            when(auditService.postAudit(AuditScenario.DOWNLOAD, FTP, "mytable", PATH, 100, "DB1")).thenReturn(false);
            when(ftpPool.getClient(FTP)).thenReturn(ftpClient, mock(FtpClient.class));

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile, opts().build());
            assertEquals(ColumnNames.STATUS_ERROR, r.getStatus());
            assertEquals(100, r.getRecordCount());
        }
    }

    // ==================== Bucket mode ====================

    @Nested
    @DisplayName("Bucket")
    class Bucket {

        private static final String FV = "EAST";

        private void mockBucketGen(String fv) throws Exception {
            FieldMapping m = new FieldMapping();
            when(fieldMappingBuilder.buildForDownload(config)).thenReturn(m);
            when(converterFactory.get("CSV")).thenReturn(fileConverter);
            when(ftpClient.getOutputStream(PATH)).thenReturn(mock(OutputStream.class));
            when(targetTableRepository.streamBucketData("DB1", "mytable", "REGION", fv))
                    .thenReturn(mock(TargetTableRepository.DataStream.class));
        }

        private DownloadSupport.PipelineOptions.PipelineOptionsBuilder opts(String fv, Integer ac) {
            return DownloadSupport.PipelineOptions.builder()
                    .wholeTable(false).fieldValue(fv).expectedAuditCount(ac)
                    .enablePreCheck(true).enableOverwriteCheck(false).enablePostAudit(true);
        }

        @Test
        @DisplayName("with preAuditByBucket")
        void withPreAudit() throws Exception {
            config.setPostOperations("SUB:/s.flg;L");
            when(auditService.preAuditByBucket("mytable", "REGION", FV, 50, "DB1")).thenReturn(50);
            mockEmptyOk(50);
            mockFtp();
            mockBucketGen(FV);
            mockPreCheckOk();
            mockPostAuditOk(50);

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile, opts(FV, 50).build());
            assertTrue(r.isSuccess());
            assertEquals(50, r.getRecordCount());
            verify(targetTableRepository).streamBucketData("DB1", "mytable", "REGION", FV);
        }

        @Test
        @DisplayName("countByBucket when auditCount null")
        void countByBucket() throws Exception {
            when(targetTableRepository.countByBucket("DB1", "mytable", "REGION", FV)).thenReturn(30);
            mockEmptyOk(30);
            mockFtp();
            mockBucketGen(FV);
            mockPreCheckOk();
            mockPostAuditOk(30);

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile, opts(FV, null).build());
            assertTrue(r.isSuccess());
            assertEquals(30, r.getRecordCount());
            verify(targetTableRepository).countByBucket("DB1", "mytable", "REGION", FV);
        }

        @Test
        @DisplayName("enablePostAudit=false skips post-audit")
        void skipPostAudit() throws Exception {
            when(auditService.preAuditByBucket("mytable", "REGION", FV, 50, "DB1")).thenReturn(50);
            mockEmptyOk(50);
            mockFtp();
            mockBucketGen(FV);
            mockPreCheckOk();

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile,
                    opts(FV, 50).enablePostAudit(false).build());
            assertTrue(r.isSuccess());
            verify(auditService, never()).postAudit(any(), any(), any(), anyString(), anyInt(), anyString());
        }

        @Test
        @DisplayName("enablePreCheck=false skips pre-check")
        void skipPreCheck() throws Exception {
            when(auditService.preAuditByBucket("mytable", "REGION", FV, 50, "DB1")).thenReturn(50);
            mockEmptyOk(50);
            mockFtp();
            mockBucketGen(FV);
            mockPostAuditOk(50);

            DownloadSupport.PipelineResult r = pipeline.executePipeline(FTP, config, targetFile,
                    opts(FV, 50).enablePreCheck(false).build());
            assertTrue(r.isSuccess());
            verify(transferSupport, never()).preCheck(any(), any(), any());
        }
    }

    // ==================== Detail status ====================

    @Nested
    @DisplayName("Detail status")
    class DetailStatus {

        private Detail d;

        @BeforeEach
        void init() {
            d = new Detail();
            d.setId(10L);
        }

        private DownloadSupport.PipelineOptions.PipelineOptionsBuilder optsOver() {
            return DownloadSupport.PipelineOptions.builder()
                    .wholeTable(true).expectedAuditCount(50)
                    .enablePreCheck(true).enableOverwriteCheck(true).enablePostAudit(true)
                    .detail(d).nodeId("n1");
        }

        @Test
        @DisplayName("success -> detail SUCCESS")
        void success() throws Exception {
            config.setPostOperations("FB:/f.flg;S");
            mockPreAudit(50);
            mockEmptyOk(50);
            mockFtp();
            mockWholeTableGen(50);
            mockPreCheckOk();
            mockPostAuditOk(50);

            pipeline.executePipeline(FTP, config, targetFile, optsOver().build());
            verify(detailRepository).updateStatus(10L, "Y", "n1");
        }

        @Test
        @DisplayName("preCheck fail -> detail SKIPPED")
        void skipped() throws Exception {
            mockPreAudit(50);
            mockEmptyOk(50);
            mockFtp();
            when(transferSupport.preCheck(ftpClient, config, targetFile)).thenReturn(false);

            pipeline.executePipeline(FTP, config, targetFile, optsOver().build());
            verify(detailRepository).updateStatus(10L, "N", "n1");
        }

        @Test
        @DisplayName("FTP exception -> detail ERROR")
        void error() throws Exception {
            mockPreAudit(50);
            mockEmptyOk(50);
            mockFtp();
            mockPreCheckOk();
            when(ftpClient.getOutputStream(PATH)).thenThrow(new RuntimeException("err"));

            pipeline.executePipeline(FTP, config, targetFile, optsOver().build());
            verify(detailRepository).updateStatus(10L, "E", "n1");
        }

        @Test
        @DisplayName("null detail id -> skip update")
        void nullId() throws Exception {
            d.setId(null);
            config.setPostOperations("FB:/f.flg;S");
            mockPreAudit(50);
            mockEmptyOk(50);
            mockFtp();
            mockWholeTableGen(50);
            mockPreCheckOk();
            mockPostAuditOk(50);

            pipeline.executePipeline(FTP, config, targetFile, optsOver().build());
            verify(detailRepository, never()).updateStatus(anyLong(), anyString(), anyString());
        }
    }

    // ==================== Exception ====================

    @Nested
    @DisplayName("Exception handling")
    class ExceptionHandling {

        @Test
        @DisplayName("ftpPool.getClient throws -> ERROR")
        void poolThrows() {
            mockPreAudit(50);
            mockEmptyOk(50);
            when(ftpPool.getClient(FTP)).thenThrow(new RuntimeException("pool"));

            var r = pipeline.executePipeline(FTP, config, targetFile,
                    DownloadSupport.PipelineOptions.builder()
                            .wholeTable(true).expectedAuditCount(50)
                            .enablePreCheck(true).enableOverwriteCheck(false).enablePostAudit(true)
                            .build());
            assertEquals(ColumnNames.STATUS_ERROR, r.getStatus());
        }

        @Test
        @DisplayName("FTP exception -> client closed")
        void clientClosed() throws Exception {
            mockPreAudit(100);
            mockEmptyOk(100);
            when(ftpPool.getClient(FTP)).thenReturn(ftpClient);
            mockPreCheckOk();
            when(ftpClient.getOutputStream(PATH)).thenThrow(new RuntimeException("err"));

            pipeline.executePipeline(FTP, config, targetFile,
                    DownloadSupport.PipelineOptions.builder()
                            .wholeTable(true).expectedAuditCount(100)
                            .enablePreCheck(true).enableOverwriteCheck(true).enablePostAudit(true)
                            .build());
            verify(ftpClient).close();
        }
    }
}
