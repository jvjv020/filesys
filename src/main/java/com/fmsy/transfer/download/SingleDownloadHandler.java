package com.fmsy.transfer.download;

import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.enums.TransferScenario;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.transfer.TransferUtils;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DOWNLOAD_SINGLE 场景:整表 → 单个 FTP 文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleDownloadHandler implements DownloadHandler {

    private final FieldMappingBuilder fieldMappingBuilder;
    private final DownloadSupport support;
    private final TransferSupport transferSupport;
    private final FtpPool ftpPool;
    private final ParallelFileGenerator parallelFileGenerator;

    @Override
    public boolean supports(TransferScenario scenario, CommandType commandType) {
        return scenario == TransferScenario.DOWNLOAD_SINGLE
                && (commandType == null || commandType == CommandType.SERIAL);
    }

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Single file download");
        String ftpName = config.getFtpName();
        ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);

        // Phase 1: Fast FTP checks only (preCheck + overwrite check)
        {
            FtpClient client = ftpPool.getClient(ftpName);
            try {
                if (!transferSupport.preCheck(client, config, fileInfo)) {
                    result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed");
                    return;
                }
                if (!support.checkOverwriteAllowed(client, fileInfo.fullPath(), config.getOverwriteFlag())) {
                    result.setOutcome(0, ColumnNames.STATUS_ERROR, "Overwrite denied: completion flag exists");
                    return;
                }
            } finally {
                client.close();
            }
        }

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

        FileConverter converter = ConverterFactory.get(config.getParserType());
        FieldMapping mapping = fieldMappingBuilder.buildForDownload(config);

        EmptyDataHandling emptyHandling = config.getEmptyDataHandling();
        if (dbRecordCount == 0) {
            if (emptyHandling == EmptyDataHandling.SKIP) {
                result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Empty data, SKIP");
                return;
            }
            if (emptyHandling == EmptyDataHandling.ERROR) {
                result.setOutcome(0, ColumnNames.STATUS_ERROR, "Empty data, ERROR");
                return;
            }
        }

        // Phase 3: FTP data transfer + postProcess (borrow again)
        AtomicInteger actualCount = new AtomicInteger(dbRecordCount);
        ftpPool.withClient(ftpName, client -> {
            try (OutputStream os = client.getOutputStream(fileInfo.fullPath())) {
                int count = parallelFileGenerator.generate(os, config, converter, mapping, dbRecordCount);
                actualCount.set(count);
                client.completePendingCommand();
            }
            Map<String, String> extra = new HashMap<>();
            extra.put("C", String.valueOf(actualCount.get()));
            transferSupport.postProcess(client, config, fileInfo, extra);
        });
        int recordCount = actualCount.get();

        // postAudit (uses its own FTP client internally via AuditService)
        // 复用 dbRecordCount 避免后审计再 COUNT
        if (command.getAuditCount() != null && command.getAuditCount() >= 0) {
            boolean postAuditOk = support.postAudit(config, fileInfo.fullPath(), dbRecordCount);
            if (!postAuditOk) {
                transferSupport.executeWithClient(ftpName, client -> {
                    TransferUtils.rollbackAfterPostAuditFailure(client, fileInfo.fullPath(), "single download post-audit");
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
