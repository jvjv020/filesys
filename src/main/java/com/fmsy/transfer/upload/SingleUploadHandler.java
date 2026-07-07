package com.fmsy.transfer.upload;

import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
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
import java.util.HashMap;
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

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);

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
                        support.truncateTable(config);
                    }

                    recordCount = support.insertBatchInTx(config, dataIter, mapping);

                    // postAudit: 用 dataIter 统计的实际行数，而非 preAudit 的行数
                    int actualFileRecords = dataIter.getRecordCount();
                    if (!support.postAudit(config, actualFileRecords, recordCount)) {
                        if (config.getEmptyDataHandling() == EmptyDataHandling.ERROR) {
                            if (BooleanUtils.isYes(config.getClearTableFlag())) {
                                support.truncateTable(config);
                                log.error("Rolled back table {} due to post-audit failure", config.getTableName());
                            }
                            moveToErrorDir(client, fileInfo.fullPath());
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
