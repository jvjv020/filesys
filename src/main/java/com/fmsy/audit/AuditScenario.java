package com.fmsy.audit;

/**
 * 审计场景 — 区分 AuditService 中预审计 / 后审计的方向语义。
 *
 * <p>不同方向审计对象不同:
 * <ul>
 *   <li>{@link #UPLOAD} — FTP→DB:预审计比文件行数,后审计比 DB 记录数</li>
 *   <li>{@link #DOWNLOAD} — DB→FTP:预审计比 DB 记录数,后审计比文件行数</li>
 * </ul>
 *
 * <p>当前 AuditService 仅被 DownloadSupport / transfer/download/ChildBucketProcessor 引用(均为 DOWNLOAD),
 * UPLOAD 分支保留作为协议占位 — UploadSupport 直接实现自己的 file/DB 审计,不走 AuditService。
 */
public enum AuditScenario {
    UPLOAD,
    DOWNLOAD
}
