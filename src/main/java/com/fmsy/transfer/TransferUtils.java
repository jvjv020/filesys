package com.fmsy.transfer;

import com.fmsy.ftp.FtpClient;
import lombok.extern.slf4j.Slf4j;

/**
 * 传输工具类 — 仅保留跨场景、与注入字段无关的纯静态工具。
 *
 * <p>本类当前仅包含 {@link #rollbackAfterPostAuditFailure(FtpClient, String, String)} —
 * 后审计失败时回滚 FTP 文件,与 FtpClient 自身状态无关,适合作为静态工具。
 */
@Slf4j
public final class TransferUtils {

    private TransferUtils() {
    }

    /**
     * 后审计失败回滚 - 删除 FTP 上已生成的目标文件(P1#2 UC-12)
     *
     * <p>需求 7.5/7.6:后审计失败时回滚已生成的目标文件,避免脏数据滞留 FTP。
     * <ul>
     *   <li>DOWNLOAD:本方法删除刚生成的目标文件,允许下次重试重新生成</li>
     *   <li>UPLOAD:目标文件是 FTP 上的源数据,不应删除;调用方在 postAudit 失败时
     *       应仅回滚 DB(参考 SingleUploadHandler.handle 的 finally 块)</li>
     * </ul>
     *
     * <p>best-effort:删除失败仅记 warn,不抛异常,以避免回滚路径再次失败。
     *
     * @param client    FTP 客户端(可复用)
     * @param filePath  要删除的目标文件 FTP 路径
     * @param reason    失败原因(写入日志,便于运维排查)
     * @return true=删除成功;false=删除失败或文件不存在
     */
    public static boolean rollbackAfterPostAuditFailure(FtpClient client, String filePath, String reason) {
        if (client == null || filePath == null || filePath.isEmpty()) {
            return false;
        }
        try {
            boolean deleted = client.deleteFile(filePath);
            if (deleted) {
                log.error("Post-audit failed ({}), rolled back FTP file: {}", reason, filePath);
            } else {
                log.error("Post-audit failed ({}), FTP file does not exist or delete denied: {}",
                        reason, filePath);
            }
            return deleted;
        } catch (Exception e) {
            log.error("Failed to delete FTP file {} during post-audit rollback: {}",
                    filePath, e.getMessage());
            return false;
        }
    }
}
