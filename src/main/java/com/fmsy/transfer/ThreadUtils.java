package com.fmsy.transfer;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 线程工具 — 统一管理线程池关闭、安全休眠等操作。
 *
 * <p>从 {@link TransferSupport} 中提取的静态工具方法。
 * 原本在 TransferSupport 中的静态方法仍保留委托，确保向后兼容。
 */
@Slf4j
public final class ThreadUtils {

    private ThreadUtils() {
    }

    /**
     * 安全关闭线程池：先 shutdown，等待指定超时，超时未终止则 shutdownNow。
     *
     * @param executor  待关闭的线程池
     * @param timeout   等待时长
     * @param unit      时间单位
     * @param poolName  线程池名称（仅用于日志）
     */
    public static void shutdownExecutor(ExecutorService executor, long timeout,
                                        TimeUnit unit, String poolName) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                log.warn("{} did not terminate within {} {}, forcing shutdown",
                        poolName, timeout, unit);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /**
     * 安全休眠 — 忽略中断后恢复中断标志，不抛异常。
     *
     * @param ms 休眠毫秒数
     */
    public static void safeSleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}