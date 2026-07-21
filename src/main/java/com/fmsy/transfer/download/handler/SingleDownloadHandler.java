package com.fmsy.transfer.download.handler;

import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.transfer.download.DownloadSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DOWNLOAD_SINGLE 场景：整表数据 → 单个 FTP 文件。
 *
 * <p>
 * 委托 {@link DownloadSupport#executeWholeTablePipeline} 执行完整的单文件下载管线，
 * 管线包含以下阶段：<br>
 * 前稽核（可选）→ 空数据处理 →（FTP 会话：前操作 → 覆盖检查 → 文件生成 → 后操作）→ 后稽核（可选）
 *
 * <p>
 * <b>覆盖检查</b>：默认启用（{@code enableOverwriteCheck=true}），
 * 检查目标文件是否存在完成标志（*.FLG / *.DONE / *.READY / *.OK）。<br>
 * <b>后稽核</b>：当 {@code auditCount} 非 null 且 &ge; 0 时启用，
 * 文件行数与命令期望的稽核数比较，不一致时回滚删除已生成的 FTP 文件。
 *
 * <p>
 * Handler 非常薄，仅做路径解析和管线结果映射，不直接操 FTP 连接。
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

        DownloadSupport.PipelineResult pr = downloadSupport.executeWholeTablePipeline(
                ftpName, config, fileInfo,
                command.getAuditCount(), null,
                true, hasAudit, null, null);

        String description = deriveDescription(pr);
        result.setOutcome(pr.recordCount(), pr.status(), description);
    }

    /**
     * 将 PipelineResult 映射为 Result 的描述字符串 — 成功返回空字符串，
     * SKIPPED/ERROR 附带文件路径作为描述。
     *
     * <p>
     * 与上传的 {@code applyPipelineResult} 功能类似，但下载场景需要文件路径信息。
     */
    private static String deriveDescription(DownloadSupport.PipelineResult pr) {
        if (pr.success())
            return "";
        String path = pr.filePath();
        if (ColumnNames.STATUS_SKIPPED.equals(pr.status())) {
            return path != null ? "Skipped for " + path : "Skipped";
        }
        return path != null ? "Error for " + path : "Error";
    }
}