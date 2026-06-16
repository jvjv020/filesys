package com.fmsy.transfer.download;

import com.fmsy.transfer.TransferHandler;

/**
 * 下载方向 Handler 标记接口 — 显式声明 Handler 只处理 DOWNLOAD 场景。
 *
 * <p>Spring 注入 {@code List<DownloadHandler>} 时自动按类型过滤,
 * DownloadOrchestrator 拿到的列表只含 3 个 Download Handler(单文件/单节点/多节点),
 * 不会混入 Upload Handler。
 *
 * <p>添加新场景:
 * <ol>
 *   <li>新建类 {@code NewDownloadHandler implements DownloadHandler} 并加 {@code @Component}</li>
 *   <li>实现 {@link TransferHandler#supports(com.fmsy.enums.TransferScenario, com.fmsy.enums.CommandType)} —
 *       只接 DOWNLOAD_* 场景</li>
 *   <li>实现 {@link TransferHandler#handle}</li>
 *   <li>DownloadOrchestrator 零改动</li>
 * </ol>
 */
public interface DownloadHandler extends TransferHandler {
}
