package com.fmsy.transfer.upload;

import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.BooleanUtils;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * UPLOAD_SINGLE 场景:FTP 单文件 → 单表批量插入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleUploadHandler implements TransferHandler {

    private final FieldMappingBuilder fieldMappingBuilder;
    private final UploadSupport support;
    private final TransferSupport transferSupport;
    private final ConverterFactory converterFactory;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        String ftpName = config.getFtpName();
        ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);

        // Phase 1 (FTP): preCheck → 释放连接
        boolean preCheckOk = transferSupport.executeWithClient(ftpName, client ->
                transferSupport.preCheck(client, config, fileInfo));
        if (!preCheckOk) {
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed");
            return;
        }

        // Phase 2 (DB + FTP): preAudit(内部借还FTP) → 读文件解析 → 插入
        FileConverter converter = converterFactory.get(config.getParserType());
        int fileLineCount = support.preAudit(command.getAuditCount(), config, fileInfo.fullPath(), converter);
        if (fileLineCount < 0) {
            result.setOutcome(0, ColumnNames.STATUS_ERROR, "Pre-audit failed");
            transferSupport.executeWithClient(ftpName, client -> {
                moveToErrorDir(client, fileInfo.fullPath());
                return null;
            });
            return;
        }

        // Phase 2: read & insert (internal FTP borrow/return per file)
        // Returns record count on success, or -1 on post-audit failure (non-ERROR mode)
        int recordCount = readAndInsert(ftpName, fileInfo, config, converter, result);

        // Phase 3 (FTP): postProcess — only when phase 2 succeeded without errors
        if (recordCount >= 0) {
            transferSupport.executeWithClient(ftpName, client -> {
                transferSupport.postProcess(client, config, fileInfo, recordCount);
                return null;
            });
            result.setOutcome(recordCount, ColumnNames.STATUS_SUCCESS, "");
        }
    }

    /**
     * 读文件 → 插入 → 后审计。返回成功插入的记录数，-1 表示后审计失败(非ERROR模式)。
     * ERROR 模式的后审计失败直接抛异常。
     */
    private int readAndInsert(String ftpName, ResolvedPath fileInfo, TransferConfig config,
                               FileConverter converter, Result result) throws Exception {
        return transferSupport.executeWithClient(ftpName, client -> {
            try (InputStream is = client.getInputStream(fileInfo.fullPath())) {
                FieldMapping mapping = fieldMappingBuilder.buildForUpload(config, null);

                try (CloseableIterator<List<Map<String, Object>>> dataIter =
                        new CloseableIterator<>(converter.parse(is, mapping))) {

                    boolean truncateFirst = BooleanUtils.isYes(config.getClearTableFlag());
                    int count = support.insertBatchInTx(config, dataIter, mapping, truncateFirst);

                    int actualFileRecords = dataIter.getRecordCount();
                    if (!support.postAudit(config, actualFileRecords, count)) {
                        if (config.getEmptyDataHandling() == EmptyDataHandling.ERROR) {
                            if (BooleanUtils.isYes(config.getClearTableFlag())) {
                                support.truncateTable(config);
                                log.error("Rolled back table {} due to post-audit failure", config.getTableName());
                            }
                            moveToErrorDir(client, fileInfo.fullPath());
                            throw new RuntimeException(
                                    "Post-audit failed: record count mismatch for " + fileInfo.fullPath());
                        }
                        result.setOutcome(0, ColumnNames.STATUS_ERROR,
                                "Post-audit failed: record count mismatch for " + fileInfo.fullPath());
                        return -1;
                    }
                    return count;
                }
            }
        });
    }

    private void moveToErrorDir(FtpClient client, String filePath) {
        try {
            String moved = client.moveToErrorDir(filePath);
            if (moved != null) {
                log.info("UC-14: moved failed file to {}", moved);
            }
        } catch (Exception ex) {
            log.error("UC-14: failed to move error file {}: {}", filePath, ex.getMessage());
        }
    }
}
