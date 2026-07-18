package com.fmsy.util;

/**
 * 系统常量定义
 *
 * 常量说明：
 * - DEFAULT_BATCH_SIZE: 批量处理大小（1000条）
 * - MAX_RETRIES: 最大重试次数
 * - MONITOR_MAX_ITERATIONS: 子命令监控最大轮询次数
 * - MONITOR_INTERVAL_MS: 子命令监控轮询间隔（毫秒）
 * - TEMP_DIR_NAME: 临时文件目录名
 * - MERGE_POLL_INTERVAL_MS: 合流程轮询间隔
 * - TEMP_FILE_SUFFIX: 临时文件扩展名
 * - SPLIT_DONE_FLAG: 拆分完成标记
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

    // ==================== 多节点下发拆合流程常量 ====================

    /** 临时文件目录名（位于目标文件父目录下） */
    public static final String TEMP_DIR_NAME = "temp";
    /** 合流程轮询间隔（毫秒） */
    public static final int MERGE_POLL_INTERVAL_MS = 3000;
    /** 临时文件扩展名 */
    public static final String TEMP_FILE_SUFFIX = ".tmp";
    /** 拆分完成标记（写入主指令 extra_info） */
    public static final String SPLIT_DONE_FLAG = "SPLIT_DONE";

    private SystemConstants() {}
}
