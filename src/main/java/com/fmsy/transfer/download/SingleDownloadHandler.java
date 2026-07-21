package com.fmsy.transfer.download;

import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DOWNLOAD_SINGLE 场景:整表 → 单个 FTP 文件。
 *
 * <p>显式编排各阶段:
 * <ol>
 *   <li>前稽核/计数 (DB-only)</li>
 *   <li>空数据处理</li>
 *   <li>FTP 会话:前操作 → 覆盖检查 → 生成文件 → 后操作</li>
 *   <li>后稽核</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleDownloadHandler implements TransferHandler {

    private final DownloadSupport downloadSupport;
    private final TransferSupport transferSupport;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Single file download, command={}, table={}",
                command.getId(), config.getTableName());
        String ftpName = config.getFtpName();
        ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);
        String filePath = fileInfo != null ? fileInfo.fullPath() : null;
        boolean hasAudit = command.getAuditCount() != null && command.getAuditCount() >= 0;

        // ===== Phase 1: 前稽核/计数 (DB-only) =====
        int recordCount;
        if (hasAudit) {
            recordCount = downloadSupport.preAudit(config, command.getAuditCount());
            if (recordCount < 0) {
                log.warn("Pre-audit failed for {}, auditCount={}", filePath, command.getAuditCount());
                result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-audit failed");
                return;
            }
        } else {
            recordCount = downloadSupport.countRecords(config);
        }

        // ===== Phase 2: 空数据处理 =====
        if (recordCount == 0) {
            if (!transferSupport.handleEmptyData(0, config.getEmptyDataHandling())) {
                String status = config.getEmptyDataHandling() == EmptyDataHandling.ERROR
                        ? ColumnNames.STATUS_ERROR : ColumnNames.STATUS_SKIPPED;
                log.warn("Empty data handling ({}) for {}", config.getEmptyDataHandling(), filePath);
                result.setOutcome(0, status, "Empty data");
                return;
            }
        }

        // ===== Phase 3-5: FTP 传输 + 后稽核 =====
        try {
            transferSupport.executeWithClient(ftpName, client -> {
                // 3a: 前操作
                if (!downloadSupport.preCheckAndMkdirs(client, config, fileInfo, filePath)) {
                    log.warn("Pre-check failed for {}", filePath);
                    result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed");
                    return null;
                }

                // 3b: 覆盖检查
                if (!downloadSupport.checkOverwriteAllowed(client, filePath, config.getOverwriteFlag())) {
                    log.warn("Overwrite denied for {}", filePath);
                    result.setOutcome(0, ColumnNames.STATUS_ERROR, "Overwrite denied");
                    return null;
                }

                // 3c: 生成文件(整表模式)
                int count = downloadSupport.generateFile(client, config, true, null, null, recordCount, filePath);

                // 3d: 后操作(全部类型)
                downloadSupport.postProcess(client, config, fileInfo, count, null);

                // Phase 4: 后稽核
                if (hasAudit && !downloadSupport.postAudit(config, filePath, count)) {
                    log.error("Post-audit failed for {}, rolling back file", filePath);
                    DownloadSupport.rollbackAfterPostAuditFailure(client, filePath, "post-audit");
                    result.setOutcome(count, ColumnNames.STATUS_ERROR, "Post-audit failed");
                    return null;
                }

                log.info("Download completed: {}, records={}", filePath, count);
                result.setOutcome(count, ColumnNames.STATUS_SUCCESS, "");
                return null;
            });
        } catch (Exception e) {
            log.error("Download failed for {}: {}", filePath, e.getMessage(), e);
            result.setOutcome(0, ColumnNames.STATUS_ERROR, e.getMessage());
        }
    }
}