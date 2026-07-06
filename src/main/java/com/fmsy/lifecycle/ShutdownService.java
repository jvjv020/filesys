package com.fmsy.lifecycle;

import com.fmsy.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 关闭服务 - 优雅关闭管理
 *
 * 功能说明：
 * - 管理应用关闭流程，确保正在处理的任务完成
 * - 使用 AtomicBoolean 防止重复关闭
 * - 使用 AtomicInteger 跟踪在飞任务数 + wait/notify 完成事件
 *
 * 关闭流程：
 * 1. initiateShutdown: 标记关闭状态，不再接受新任务
 * 2. awaitTasksComplete: 等待所有在飞任务完成（带超时）
 * 3. shutdownComplete: 确认关闭完成
 *
 * 任务计数：
 * - beginTask: 任务开始时调用,递增计数
 * - endTask:   任务完成(成功或异常)时调用,递减计数,归零时唤醒等待者
 */
@Slf4j
@Service
public class ShutdownService {

    /** 是否正在关闭 */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /** 当前在飞任务计数 */
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    /** 任务计数归零的同步锁与等待者 */
    private final Object completionLock = new Object();
    /** 关闭等待超时秒数（默认300秒=5分钟，可由appConfig.taskTimeoutHours覆盖） */
    private int timeoutSeconds = 300;

    /**
     * 设置关闭超时（可选注入，@Autowired可选依赖）
     */
    @Autowired(required = false)
    public void configureTimeout(AppConfig appConfig) {
        if (appConfig != null && appConfig.getPolling() != null && appConfig.getPolling().getTaskTimeoutHours() > 0) {
            // 取任务超时小时数 × 60 转分钟，再取较小者作为最大等待秒数（保底用30秒）
            int fromConfig = appConfig.getPolling().getTaskTimeoutHours() * 60;
            this.timeoutSeconds = Math.min(Math.max(fromConfig, 30), 3600);
        }
    }

    /** 检查是否正在关闭 */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /** 发起关闭 - 标记状态，不再接受新任务 */
    public void initiateShutdown() {
        shuttingDown.set(true);
        log.info("Shutdown initiated, no new tasks will be accepted (active={})", activeTaskCount.get());
    }

    /**
     * 任务开始 - 递增在飞任务计数
     *
     * <p>若在关闭状态后才调用,直接拒绝(返回 false),调用方应放弃任务;
     * 正常状态下返回 true。
     */
    public boolean beginTask() {
        if (shuttingDown.get()) {
            return false;
        }
        activeTaskCount.incrementAndGet();
        return true;
    }

    /**
     * 任务结束 - 递减在飞任务计数,归零时唤醒等待者
     */
    public void endTask() {
        int remaining = activeTaskCount.decrementAndGet();
        if (remaining < 0) {
            // 防御:不应发生,但修正避免 wait/notify 永远不唤醒
            log.warn("endTask called with no matching beginTask (counter went negative)");
            activeTaskCount.set(0);
            remaining = 0;
        }
        if (remaining == 0) {
            synchronized (completionLock) {
                completionLock.notifyAll();
            }
        }
    }

    /**
     * 等待所有在飞任务完成
     *
     * @param timeoutSeconds 最大等待秒数
     */
    public void awaitTasksComplete(int timeoutSeconds) {
        int initial = activeTaskCount.get();
        if (initial == 0) {
            return;
        }
        log.info("Waiting for {} in-flight tasks to complete, max {} seconds", initial, timeoutSeconds);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        synchronized (completionLock) {
            while (activeTaskCount.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    log.warn("Tasks did not complete within {} seconds, {} still active",
                            timeoutSeconds, activeTaskCount.get());
                    return;
                }
                try {
                    completionLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Await termination interrupted");
                    return;
                }
            }
        }
        log.info("All tasks completed within timeout");
    }

    /** 关闭完成确认 */
    public void shutdownComplete() {
        log.info("Shutdown complete");
    }

    /**
     * Spring上下文关闭事件监听。
     *
     * <p>P1#5修复:Spring 容器关闭时 {@code @PreDestroy} 与
     * {@link ContextClosedEvent} 都会触发,两路都执行关闭序列会造成日志重复。
     * 这里只保留 {@code ContextClosedEvent} 作为统一入口,@PreDestroy 删除。
     */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent event) {
        log.info("ContextClosedEvent received, initiating shutdown sequence");
        initiateShutdown();
        awaitTasksComplete(timeoutSeconds);
        shutdownComplete();
    }
}