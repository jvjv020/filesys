package com.fmsy.exception;

/**
 * 传输异常 — 文件传输过程中的异常统一封装,含错误码便于程序化处理。
 *
 * <p>当前唯一已使用错误码:
 * <ul>
 *   <li>{@code CONFIG_NOT_FOUND} — ConfigLoaderService.getConfigOrThrow 在配置缺失时抛出</li>
 * </ul>
 *
 * <p>简化原则:不分业务子异常(上传/下载/FTP/DB),单一 RuntimeException 已足够;
 * 业务方一般在边界 catch 后写结果表置 ERROR 状态,不依赖异常类型分流。
 */
public class TransferException extends RuntimeException {

    private final String errorCode;

    public TransferException(String message) {
        super(message);
        this.errorCode = "TRANSFER_ERROR";
    }

    public TransferException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "TRANSFER_ERROR";
    }

    public TransferException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TransferException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}