package com.fmsy.transfer;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * transfer 包公用工具方法 — 无状态静态方法集合。
 *
 * <p>包含线程池关闭等跨 Handler 共享逻辑。</p>
 */
@Slf4j
public final class TransferUtils {

    private TransferUtils() {
        // 工具类，禁止实例化
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
}
