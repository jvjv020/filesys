package com.fmsy.transfer.download;

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
 * <p>委托 {@link DownloadSupport#executePipeline} 执行完整的单文件下载管线。
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

        boolean hasAudit = command.getAuditCount() != null && command.getAuditCount() >= 0;

        DownloadSupport.PipelineOptions opts = DownloadSupport.PipelineOptions.builder()
                .wholeTable(true)
                .fieldValue(null)
                .expectedAuditCount(command.getAuditCount())
                .enablePreCheck(true)
                .enableOverwriteCheck(true)
                .enablePostAudit(hasAudit)
                .postOpsFilter(null)
                .detail(null)
                .nodeId(null)
                .build();

        DownloadSupport.PipelineResult pr = downloadSupport.executePipeline(
                ftpName, config, fileInfo, opts);

        String description = deriveDescription(pr);
        result.setOutcome(pr.getRecordCount(), pr.getStatus(), description);
    }

    private static String deriveDescription(DownloadSupport.PipelineResult pr) {
        if (pr.isSuccess()) return "";
        String path = pr.getFilePath();
        if (ColumnNames.STATUS_SKIPPED.equals(pr.getStatus())) {
            return path != null ? "Skipped for " + path : "Skipped";
        }
        return path != null ? "Error for " + path : "Error";
    }
}
