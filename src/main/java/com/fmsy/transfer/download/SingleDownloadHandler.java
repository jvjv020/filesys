package com.fmsy.transfer.download;

import com.fmsy.converter.ConverterFactory;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * DOWNLOAD_SINGLE 场景:整表 → 单个 FTP 文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleDownloadHandler implements TransferHandler {

    private final FieldMappingBuilder fieldMappingBuilder;
    private final DownloadSupport support;
    private final TransferSupport transferSupport;
    private final ParallelFileGenerator parallelFileGenerator;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Single file download");
        String ftpName = config.getFtpName();
        ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);

        // Phase 1: Fast FTP checks only (preCheck + overwrite check)
        boolean checksPassed = transferSupport.executeWithClient(ftpName, client -> {
            if (!transferSupport.preCheck(client, config, fileInfo)) {
                result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed");
                return false;
            }
            if (!support.checkOverwriteAllowed(client, fileInfo.fullPath(), config.getOverwriteFlag())) {
                result.setOutcome(0, ColumnNames.STATUS_ERROR, "Overwrite denied: completion flag exists");
                return false;
            }
            return true;
        });
        if (!checksPassed) return;

        // Phase 2: DB-only work (no FTP client held)
        int dbRecordCount = -1;
        if (command.getAuditCount() != null && command.getAuditCount() >= 0) {
            dbRecordCount = support.preAudit(config, command.getAuditCount());
            if (dbRecordCount < 0) {
                result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-audit failed");
                return;
            }
        }
        if (dbRecordCount < 0) {
            dbRecordCount = support.countRecords(config);
        }

        if (dbRecordCount == 0 && !transferSupport.handleEmptyData(dbRecordCount, config.getEmptyDataHandling())) {
            result.setOutcome(0,
                    config.getEmptyDataHandling() == EmptyDataHandling.SKIP ? ColumnNames.STATUS_SKIPPED : ColumnNames.STATUS_ERROR,
                    "Empty data, " + config.getEmptyDataHandling());
            return;
        }

        // Phase 3: FTP data transfer + postProcess
        int recordCount = transferSupport.executeWithClient(ftpName, client -> {
            var converter = ConverterFactory.get(config.getParserType());
            var mapping = fieldMappingBuilder.buildForDownload(config);
            int count;
            try (OutputStream os = client.getOutputStream(fileInfo.fullPath())) {
                count = parallelFileGenerator.generate(os, config, converter, mapping, dbRecordCount);
                client.completePendingCommand();
            }
            Map<String, String> extra = new HashMap<>();
            extra.put("C", String.valueOf(count));
            transferSupport.postProcess(client, config, fileInfo, extra);
            return count;
        });

        // postAudit (uses its own FTP client internally via AuditService)
        if (command.getAuditCount() != null && command.getAuditCount() >= 0) {
            boolean postAuditOk = support.postAudit(config, fileInfo.fullPath(), dbRecordCount);
            if (!postAuditOk) {
                transferSupport.executeWithClient(ftpName, client -> {
                    DownloadSupport.rollbackAfterPostAuditFailure(client, fileInfo.fullPath(), "single download post-audit");
                    return null;
                });
                result.setOutcome(0, ColumnNames.STATUS_ERROR,
                        "Post-audit failed: record count mismatch for " + fileInfo.fullPath());
                return;
            }
        }

        log.info("Downloaded single file: {}", fileInfo.fullPath());
        result.setOutcome(recordCount, ColumnNames.STATUS_SUCCESS, "");
    }
}
