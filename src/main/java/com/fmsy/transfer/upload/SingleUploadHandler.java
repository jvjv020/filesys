package com.fmsy.transfer.upload;

import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
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
        boolean preCheckOk = transferSupport.executeWithClient(ftpName,
                client -> transferSupport.preCheck(client, config, fileInfo));
        if (!preCheckOk) {
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed: file not found");
            return;
        }

        // Phase 2 (DB + FTP): preAudit(内部借还FTP) → 读文件解析 → 插入
        FileConverter converter = converterFactory.get(config.getParserType());
        int preAuditResult = support.preAudit(command.getAuditCount(), config, fileInfo.fullPath(), converter);
        if (preAuditResult < 0) {
            String errorMsg = command.getAuditCount() != null
                    ? "Pre-audit failed: expected " + command.getAuditCount() + " records for " + fileInfo.fullPath()
                    : "Pre-audit failed for " + fileInfo.fullPath();
            result.setOutcome(0, ColumnNames.STATUS_ERROR, errorMsg);
            transferSupport.executeWithClient(ftpName, client -> {
                moveToErrorDir(client, fileInfo.fullPath());
                return null;
            });
            return;
        }

        // Phase 2: read & insert with post-audit inside transaction (internal FTP borrow/return per file)
        int recordCount = readAndInsert(ftpName, fileInfo, config, converter, result);

        // Phase 3 (FTP): postProcess — only when phase 2 succeeded without errors
        if (recordCount >= 0) {
            try {
                transferSupport.executeWithClient(ftpName, client -> {
                    transferSupport.postProcess(client, config, fileInfo, recordCount);
                    return null;
                });
            } catch (Exception e) {
                log.error("Phase 3 (postProcess) failed for {}: {}", fileInfo.fullPath(), e.getMessage(), e);
                result.setOutcome(recordCount, ColumnNames.STATUS_ERROR,
                        "Data uploaded successfully (" + recordCount + " records) but post-processing failed: "
                                + e.getMessage());
                return;
            }
            result.setOutcome(recordCount, ColumnNames.STATUS_SUCCESS, "");
        }
    }

    /**
     * 读文件 → 插入 → 后审计(在事务内,失败时可回滚)。
     *
     * <p>使用 {@link UploadSupport#insertAndVerifyInTx} 方法,后审计在事务内完成,
     * ERROR 模式时事务回滚,清表/增量模式均支持全量撤销;
     * 非 ERROR 模式时数据提交但 result 记录错误状态。
     *
     * @return 成功插入的记录数, -1 表示后审计失败(非ERROR模式)
     */
    private int readAndInsert(String ftpName, ResolvedPath fileInfo, TransferConfig config,
            FileConverter converter, Result result) throws Exception {
        return transferSupport.executeWithClient(ftpName, client -> {
            try (InputStream is = client.getInputStream(fileInfo.fullPath())) {
                FieldMapping mapping = fieldMappingBuilder.buildForUpload(config, null);

                try (CloseableIterator<List<Map<String, Object>>> dataIter = new CloseableIterator<>(
                        converter.parse(is, mapping))) {

                    boolean truncateFirst = BooleanUtils.isYes(config.getClearTableFlag());
                    int count = support.insertAndVerifyInTx(config, dataIter, mapping, truncateFirst, result);

                    // 非ERROR模式后审计失败时, result已是 ERROR 状态
                    if (ColumnNames.STATUS_ERROR.equals(result.getResult())) {
                        moveToErrorDir(client, fileInfo.fullPath());
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
