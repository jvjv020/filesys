package com.fmsy.enums;

/**
 * 传输场景枚举
 *
 * 场景说明：
 * - UPLOAD_SINGLE: 单文件上传（一个文件匹配一个占位符）
 * - UPLOAD_MULTI: 多文件上传（目录匹配或明细表指定）
 * - DOWNLOAD_SINGLE: 单文件下载（整个表数据生成一个文件）
 * - DOWNLOAD_SINGLE_NODE: 单节点多文件下载（按拆分字段分桶，每组生成一个文件）
 * - DOWNLOAD_MULTI_NODE: 多节点协调下载（主命令分桶，各节点竞争处理子命令）
 */
public enum TransferScenario {
    UPLOAD_SINGLE,        // 单文件上传
    UPLOAD_MULTI,         // 多文件上传
    DOWNLOAD_SINGLE,      // 单文件下载
    DOWNLOAD_SINGLE_NODE, // 单节点多文件下载
    DOWNLOAD_MULTI_NODE;  // 多节点协调下载

    /**
     * 是否为上传方向。用于 PollingService / TransferService 等需要按方向分发的场景,
     * 替代 {@code name().startsWith("UPLOAD")} 散弹式判断。
     */
    public boolean isUpload() {
        return this == UPLOAD_SINGLE || this == UPLOAD_MULTI;
    }
}
