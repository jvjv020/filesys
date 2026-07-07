package com.fmsy.transfer.upload;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TargetTableRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UPLOAD_SINGLE 场景:FTP 单文件 → 单表批量插入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleUploadHandler implements TransferHandler {

    private final TargetTableRepository targetTableRepository;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final UploadSupport support;
    private final TransferSupport transferSupport;
    private final DataSourceConfig.DbPool dbPool;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);
        AtomicBoolean needMoveToError = new AtomicBoolean(false);

        try {
            transferSupport.executeWithClient(config.getFtpName(), client -> {
                if (!transferSupport.preCheck(client, config, fileInfo)) {
                    result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed");
                    return null;
                }

                FileConverter converter = ConverterFactory.get(config.getParserType());
                int fileLineCount = support.preAudit(command.getAuditCount(), config, fileInfo.fullPath(), converter);
                if (fileLineCount < 0) {
                    result.setOutcome(0, ColumnNames.STATUS_ERROR, "Pre-audit failed");
                    moveToErrorDir(client, fileInfo.fullPath());
                    return null;
                }

                int recordCount;
                try (InputStream is = client.getInputStream(fileInfo.fullPath())) {
                    FieldMapping mapping = fieldMappingBuilder.buildForUpload(config, null);

                    try (CloseableIterator<List<Map<String, Object>>> dataIter =
                            new CloseableIterator<>(converter.parse(is, mapping))) {

                        if (BooleanUtils.isYes(config.getClearTableFlag())) {
                            dbPool.getTransactionTemplate(config.getDbName()).execute(status -> {
                                targetTableRepository.truncate(config.getDbName(), config.getTableName());
                                return null;
                            });
                        }

                        recordCount = support.insertBatchInTx(config, dataIter, mapping);

                        // postAudit: 用 dataIter 统计的实际行数，而非 preAudit 的行数
                        int actualFileRecords = dataIter.getRecordCount();
                        if (!support.postAudit(config, actualFileRecords, recordCount)) {
                            if (config.getEmptyDataHandling() == EmptyDataHandling.ERROR) {
                                needMoveToError.set(true);
                                if (BooleanUtils.isYes(config.getClearTableFlag())) {
                                    dbPool.getTransactionTemplate(config.getDbName()).execute(status -> {
                                        targetTableRepository.truncate(config.getDbName(), config.getTableName());
                                        return null;
                                    });
                                    log.error("Rolled back table {} due to post-audit failure", config.getTableName());
                                }
                                throw new RuntimeException(
                                        "Post-audit failed: record count mismatch for " + fileInfo.fullPath());
                            }
                        }
                    }
                }

                Map<String, String> extra = new HashMap<>();
                extra.put("C", String.valueOf(recordCount));
                transferSupport.postProcess(client, config, fileInfo, extra);
                result.setOutcome(recordCount, ColumnNames.STATUS_SUCCESS, "");
                return null;
            });
        } finally {
            if (needMoveToError.get()) {
                transferSupport.executeWithClient(config.getFtpName(), errorClient -> {
                    moveToErrorDir(errorClient, fileInfo.fullPath());
                    return null;
                });
            }
        }
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
