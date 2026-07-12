package com.fmsy.transfer.upload;

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
 * UPLOAD_SINGLE 场景:FTP 单文件 → 单表批量插入。
 *
 * <p>完整上传流程委托给 {@link UploadSupport#processSingleFile}，
 * 本 Handler 只负责路径解析和结果表状态设置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleUploadHandler implements TransferHandler {

    private final UploadSupport support;
    private final TransferSupport transferSupport;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);

        UploadSupport.UploadResult r = support.processSingleFile(
                config.getFtpName(), command.getAuditCount(), config,
                fileInfo.fullPath(), null);

        if (ColumnNames.STATUS_SKIPPED.equals(r.status())) {
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed: flag not found");
            return;
        }
        if (ColumnNames.STATUS_ERROR.equals(r.status())) {
            result.setOutcome(0, ColumnNames.STATUS_ERROR, r.failedCount() > 0 ? "" : "Upload failed");
            return;
        }
        // status=null 表示成功
        result.setOutcome(r.records(), ColumnNames.STATUS_SUCCESS, "");
    }
}
