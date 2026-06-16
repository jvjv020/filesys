package com.fmsy.util;

import org.slf4j.MDC;

/**
 * 日志工具类
 *
 * 功能说明：
 * - 日志MDC（Mapped Diagnostic Context）管理
 * - 支持taskId和nodeId的链路追踪
 */
public final class LogUtils {

    private LogUtils() {
    }

    /** 设置任务ID到MDC */
    public static void setTaskId(Long taskId) {
        if (taskId != null) {
            MDC.put("taskId", String.valueOf(taskId));
        }
    }

    /** 设置节点ID到MDC */
    public static void setNodeId(String nodeId) {
        if (nodeId != null) {
            MDC.put("nodeId", nodeId);
        }
    }
}