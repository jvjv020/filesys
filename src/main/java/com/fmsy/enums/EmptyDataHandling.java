package com.fmsy.enums;

/**
 * 空数据处理枚举
 *
 * 处理方式：
 * - ERROR: 空数据时报错
 * - ALLOW: 允许空数据继续处理
 * - SEND_EMPTY: 发送空文件
 * - SKIP: 跳过该处理
 */
public enum EmptyDataHandling {
    ERROR,      // 报错
    ALLOW,      // 允许
    SEND_EMPTY, // 发送空文件
    SKIP        // 跳过
}