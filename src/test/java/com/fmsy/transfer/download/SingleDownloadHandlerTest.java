package com.fmsy.transfer.download;

import com.fmsy.enums.CommandType;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
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

import org.mockito.ArgumentCaptor;

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
        when(downloadSupport.executePipeline(anyString(), eq(config), any(ResolvedPath.class), any()))
                .thenReturn(new DownloadSupport.PipelineResult(100, true, ColumnNames.STATUS_SUCCESS, "/data/out.csv"));

        handler.handle(command, config, result);

        assertEquals(100, result.getRecordCount());
        assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        verify(downloadSupport).executePipeline(eq("ftp1"), eq(config), eq(fileInfo), any());
    }

    @Test
    @DisplayName("should map pipeline SKIPPED to result")
    void shouldMapSkipped() throws Exception {
        when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
        when(downloadSupport.executePipeline(anyString(), any(), any(), any()))
                .thenReturn(new DownloadSupport.PipelineResult(0, false, ColumnNames.STATUS_SKIPPED, "/data/out.csv"));

        handler.handle(command, config, result);

        assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
    }

    @Test
    @DisplayName("should map pipeline ERROR to result")
    void shouldMapError() throws Exception {
        when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
        when(downloadSupport.executePipeline(anyString(), any(), any(), any()))
                .thenReturn(new DownloadSupport.PipelineResult(0, false, ColumnNames.STATUS_ERROR, "/data/out.csv"));

        handler.handle(command, config, result);

        assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
    }

    @Test
    @DisplayName("should disable postAudit when auditCount is null")
    void shouldDisablePostAuditWhenAuditCountNull() throws Exception {
        command.setAuditCount(null);
        when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
        when(downloadSupport.executePipeline(anyString(), any(), any(), any()))
                .thenReturn(new DownloadSupport.PipelineResult(100, true, ColumnNames.STATUS_SUCCESS, "/data/out.csv"));

        handler.handle(command, config, result);

        assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
        // Verify pipeline options had enablePostAudit=false
        ArgumentCaptor<DownloadSupport.PipelineOptions> captor = ArgumentCaptor.captor();
        verify(downloadSupport).executePipeline(anyString(), any(), any(), captor.capture());
        assertFalse(captor.getValue().enablePostAudit);
    }
}
