package com.fmsy.transfer.upload;

import com.fmsy.transfer.TransferHandler;

/**
 * 上传方向 Handler 标记接口 — 显式声明 Handler 只处理 UPLOAD 场景。
 *
 * <p>Spring 注入 {@code List<UploadHandler>} 时自动按类型过滤,
 * UploadOrchestrator 拿到的列表只含 3 个 Upload Handler(单文件/多目录/多批),
 * 不会混入 Download Handler。
 *
 * <p>添加新场景:
 * <ol>
 *   <li>新建类 {@code NewUploadHandler implements UploadHandler} 并加 {@code @Component}</li>
 *   <li>实现 {@link TransferHandler#supports(com.fmsy.enums.TransferScenario, com.fmsy.enums.CommandType)} —
 *       只接 UPLOAD_* 场景</li>
 *   <li>实现 {@link TransferHandler#handle}</li>
 *   <li>UploadOrchestrator 零改动</li>
 * </ol>
 */
public interface UploadHandler extends TransferHandler {
}
