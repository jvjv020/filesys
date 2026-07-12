package com.fmsy.exception;

/**
 * 标志文件比对异常 — FLAG 前置操作中模式比对不通过时抛出。
 *
 * <p>调用方(Handler)捕获此异常后应将指令标记为 ERROR(而非 SKIP),
 * 异常消息中包含比对的具体值(期望值、实际值、比较符、模式码)。
 */
public class FlagCheckException extends RuntimeException {

    public FlagCheckException(String message) {
        super(message);
    }

    public FlagCheckException(String message, Throwable cause) {
        super(message, cause);
    }
}
