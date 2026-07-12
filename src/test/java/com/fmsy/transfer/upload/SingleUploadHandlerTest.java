package com.fmsy.transfer.upload;

import com.fmsy.enums.EmptyDataHandling;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SingleUploadHandler 重构后测试 — 使用 processSingleFile 统一处理。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SingleUploadHandler Tests (using processSingleFile)")
class SingleUploadHandlerTest {

    @Mock
    private UploadSupport uploadSupport;

    @Mock
    private TransferSupport transferSupport;

    private SingleUploadHandler handler;

    private Command command;
    private TransferConfig config;
    private Result result;
    private ResolvedPath fileInfo;

    @BeforeEach
    void setUp() {
        handler = new SingleUploadHandler(uploadSupport, transferSupport);

        command = new Command();
        command.setId(1L);
        command.setCategoryCode("CAT001");
        command.setControlCode("CTRL001");
        command.setAuditCount(100);

        config = new TransferConfig();
        config.setFtpName("ftp1");
        config.setFilePath("/data/{YYYYMMDD}/file.csv");
        config.setParserType("CSV");
        config.setTableName("mytable");
        config.setDbName("DB1");

        result = new Result();
        fileInfo = ResolvedPath.of("/data/20260615/file.csv");
    }

    // ==================== preCheck 失败 ====================

    @Nested
    @DisplayName("handle - preCheck 返回 false（标志文件不存在）")
    class HandlePreCheckReturnsFalse {

        @Test
        @DisplayName("应设置 SKIPPED 状态")
        void shouldSetSkippedWhenPreCheckReturnsFalse() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(uploadSupport.processSingleFile(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(new UploadSupport.UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SKIPPED, result.getResult());
            assertEquals(0, result.getRecordCount());
        }
    }

    // ==================== 失败流程（processSingleFile 返回 ERROR） ====================

    @Nested
    @DisplayName("handle - processSingleFile 返回 ERROR")
    class HandleProcessSingleFileError {

        @Test
        @DisplayName("应设置 ERROR 状态，不抛异常")
        void shouldSetErrorWithoutThrowing() {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(uploadSupport.processSingleFile(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(new UploadSupport.UploadResult(0, 0, 0, 1, ColumnNames.STATUS_ERROR));

            // processSingleFile 不抛异常，内部已处理文件迁移
            assertDoesNotThrow(() -> handler.handle(command, config, result));

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
            assertEquals(0, result.getRecordCount());
        }
    }

    // ==================== 成功流程 ====================

    @Nested
    @DisplayName("handle - 成功流程")
    class HandleSuccessFlow {

        @BeforeEach
        void setup() {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            when(uploadSupport.processSingleFile(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(new UploadSupport.UploadResult(100, 1, 0, 0, null));
        }

        @Test
        @DisplayName("应设置 SUCCESS 状态和记录数")
        void shouldSetSuccessWithRecordCount() throws Exception {
            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            assertEquals(100, result.getRecordCount());
        }
    }

    // ==================== Phase3 postProcess 失败 ====================

    @Nested
    @DisplayName("handle - Phase3 postProcess 失败")
    class HandlePhase3Failure {

        @Test
        @DisplayName("processSingleFile 内部处理 postProcess 异常，返回成功结果")
        void shouldSetSuccessWhenPostProcessFails() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            // uploadSingleFile 内部吞掉 postProcess 异常，processSingleFile 返回成功
            when(uploadSupport.processSingleFile(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(new UploadSupport.UploadResult(100, 1, 0, 0, null));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            assertEquals(100, result.getRecordCount());
        }
    }

    // ==================== processSingleFile 不再抛出 FlagCheckException ====================

    @Nested
    @DisplayName("handle - processSingleFile 将异常转为 ERROR")
    class HandleFlagCheckExceptionInsideProcessSingleFile {

        @Test
        @DisplayName("FlagCheckException 被 processSingleFile 内部处理，不传播")
        void shouldNotPropagateFlagCheckException() {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            // processSingleFile 内部捕获异常，返回 ERROR 结果
            when(uploadSupport.processSingleFile(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(new UploadSupport.UploadResult(0, 0, 0, 1, ColumnNames.STATUS_ERROR));

            // 不再抛出 FlagCheckException
            assertDoesNotThrow(() -> handler.handle(command, config, result));

            assertEquals(ColumnNames.STATUS_ERROR, result.getResult());
        }
    }

    // ==================== 空文件 ====================

    @Nested
    @DisplayName("handle - 空文件场景")
    class HandleEmptyFile {

        @Test
        @DisplayName("空文件且 handling=ALLOW，应 SUCCESS")
        void shouldBeSuccessWhenEmptyFileAndAllow() throws Exception {
            when(transferSupport.resolveFilePath(config.getFilePath(), command)).thenReturn(fileInfo);
            config.setEmptyDataHandling(EmptyDataHandling.ALLOW);
            when(uploadSupport.processSingleFile(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(new UploadSupport.UploadResult(0, 1, 0, 0, null));

            handler.handle(command, config, result);

            assertEquals(ColumnNames.STATUS_SUCCESS, result.getResult());
            assertEquals(0, result.getRecordCount());
        }
    }
}
