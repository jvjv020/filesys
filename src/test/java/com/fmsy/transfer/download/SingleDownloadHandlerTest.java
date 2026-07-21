package com.fmsy.transfer.download;

import com.fmsy.enums.CommandType;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.download.handler.SingleDownloadHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SingleDownloadHandler Tests")
class SingleDownloadHandlerTest {

    @Mock
    private DownloadSupport downloadSupport;

    @Mock
    private TransferSupport transferSupport;

    @InjectMocks
    private SingleDownloadHandler handler;

    private Command command;
    private TransferConfig config;
    private Result result;
    private ResolvedPath fileInfo;

    @BeforeEach
    void setUp() {
        command = new Command();
        command.setId(1L);
        command.setCategoryCode("CAT001");
        command.setControlCode("CTRL001");
        command.setCommandType(CommandType.SERIAL);
        command.setAuditCount(100);

        config = new TransferConfig();
        config.setFtpName("ftp1");
        config.setFilePath("/data/out.csv");
        config.setParserType("CSV");
        config.setTableName("mytable");
        config.setDbName("DB1");
        config.setOverwriteFlag("Y");

        result = new Result();
        fileInfo = ResolvedPath.of("/data/out.csv");
    }

    @Test
    @DisplayName("should delegate to pipeline and set outcome")
    void shouldDelegateToPipeline() throws Exception {
        when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
        when(downloadSupport.executeWholeTablePipeline(anyString(), eq(config), any(ResolvedPath.class),
                any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new DownloadSupport.PipelineResult(100, true, ColumnNames.STATUS_SUCCESS, "/data/out.csv"));

        handler.handle(command, config, result);

        assertEquals(100, result.getRecordCount());
        assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        verify(downloadSupport).executeWholeTablePipeline(eq("ftp1"), eq(config), eq(fileInfo),
                eq(100), isNull(), eq(true), eq(true), isNull(), isNull());
    }

    @Test
    @DisplayName("should map pipeline SKIPPED to result")
    void shouldMapSkipped() throws Exception {
        when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
        when(downloadSupport.executeWholeTablePipeline(anyString(), any(), any(),
                any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new DownloadSupport.PipelineResult(0, false, ColumnNames.STATUS_SKIPPED, "/data/out.csv"));

        handler.handle(command, config, result);

        assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
    }

    @Test
    @DisplayName("should map pipeline ERROR to result")
    void shouldMapError() throws Exception {
        when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
        when(downloadSupport.executeWholeTablePipeline(anyString(), any(), any(),
                any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new DownloadSupport.PipelineResult(0, false, ColumnNames.STATUS_ERROR, "/data/out.csv"));

        handler.handle(command, config, result);

        assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
    }

    @Test
    @DisplayName("should disable postAudit when auditCount is null")
    void shouldDisablePostAuditWhenAuditCountNull() throws Exception {
        command.setAuditCount(null);
        when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
        when(downloadSupport.executeWholeTablePipeline(anyString(), any(), any(),
                any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new DownloadSupport.PipelineResult(100, true, ColumnNames.STATUS_SUCCESS, "/data/out.csv"));

        handler.handle(command, config, result);

        assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        // 验证 enablePostAudit=false（auditCount=null → hasAudit=false → enablePostAudit=false）
        verify(downloadSupport).executeWholeTablePipeline(eq("ftp1"), eq(config), eq(fileInfo),
                isNull(), isNull(), eq(true), eq(false), isNull(), isNull());
    }
}
