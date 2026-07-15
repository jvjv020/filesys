package com.fmsy.transfer.upload;

import com.fmsy.exception.FlagCheckException;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.BooleanUtils;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * UPLOAD_SINGLE 场景：FTP 单文件 → 单表批量插入。
 *
 * <p>
 * 完整上传管线由 UploadSupport 纯方法组合而成：<br>
 * preCheck → truncateTable → insertDataAndVerify → postProcess
 *
 * <p>
 * 前稽核与后审计已合并到 insertDataAndVerify 中，在落库后使用
 * CloseableIterator.getRecordCount() 统一校验，无需额外 FTP 流。
 * 清表操作在落库之前执行，确保数据完整性。
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
        String filePath = fileInfo.fullPath();
        String ftpName = config.getFtpName();
        Integer auditCount = command.getAuditCount();

        try {
            // 在 FTP 连接上下文中执行 preCheck → insertDataAndVerify → postProcess
            // 前稽核与后审计已合并到 insertDataAndVerify 中，在落库后统一校验
            UploadSupport.UploadResult r = transferSupport.executeWithClient(ftpName, client -> {
                // Phase 1: preCheck — 标志文件检查
                UploadSupport.UploadResult checkResult = support.preCheck(client, config, fileInfo, filePath);
                if (checkResult != null) {
                    return checkResult; // SKIPPED
                }

                // Phase 2: 清表 — 落库之前
                if (BooleanUtils.isYes(config.getClearTableFlag())) {
                    support.truncateTable(config);
                }

                // Phase 3: insert + 前后审计（单事务）
                // 前稽核与后审计使用 CloseableIterator.getRecordCount() 统一校验
                int count = support.insertDataAndVerify(client, config, fileInfo, null, filePath, auditCount);

                // Phase 5: postProcess — FTP 后操作
                support.postProcess(client, config, fileInfo, count);

                return new UploadSupport.UploadResult(count, 1, 0, 0, null);
            });

            // 根据结果设置结果表状态
            if (ColumnNames.STATUS_SKIPPED.equals(r.status())) {
                result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed: flag not found");
                return;
            }
            // status=null 表示成功
            result.setOutcome(r.records(), ColumnNames.STATUS_SUCCESS, "");

        } catch (FlagCheckException e) {
            // FLAG 比对失败 → 迁文件到 error 目录
            moveFileToError(ftpName, filePath, config);
            log.warn("FLAG check failed, data file: {}, detail: {}", filePath, e.getMessage());
            result.setOutcome(0, ColumnNames.STATUS_ERROR, "FLAG check failed: " + e.getMessage());

        } catch (RuntimeException e) {
            // 前稽核/后审计失败 → 迁文件到 error 目录
            moveFileToError(ftpName, filePath, config);
            log.error("Upload failed for {}: {}", filePath, e.getMessage());
            result.setOutcome(0, ColumnNames.STATUS_ERROR, e.getMessage());
        }
    }

    /**
     * 将数据文件及配置的标志文件迁到 error 目录。
     */
    private void moveFileToError(String ftpName, String filePath, TransferConfig config) {
        try {
            transferSupport.executeWithClient(ftpName, client -> {
                support.moveDataAndFlagToErrorDir(client, filePath, config);
                return null;
            });
        } catch (Exception ex) {
            log.error("Failed to move files to error dir for {}: {}", filePath, ex.getMessage());
        }
    }
}