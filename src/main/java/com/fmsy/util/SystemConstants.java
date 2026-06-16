package com.fmsy.util;

/**
 * 系统常量定义
 *
 * 常量说明：
 * - DEFAULT_BATCH_SIZE: 批量处理大小（1000条）
 * - MAX_RETRIES: 最大重试次数
 * - MONITOR_MAX_ITERATIONS: 子命令监控最大轮询次数
 * - MONITOR_INTERVAL_MS: 子命令监控轮询间隔（毫秒）
 */
public final class SystemConstants {
    /** 批量处理大小 */
    public static final int DEFAULT_BATCH_SIZE = 1000;
    /** 最大重试次数 */
    public static final int MAX_RETRIES = 1;
    /** 子命令监控最大轮询次数 */
    public static final int MONITOR_MAX_ITERATIONS = 60;
    /** 子命令监控轮询间隔（毫秒） */
    public static final int MONITOR_INTERVAL_MS = 5000;

    private SystemConstants() {}
}