package com.fmsy.model;

import lombok.Data;

/**
 * 消息配置实体 — 消息配置表的一条记录。
 *
 * <p>MSG 后置操作使用 {@link #categoryCode} + {@link #controlCode}（对应传输配置的代号）
 * 来查找消息配置，决定发送通道、目标地址和消息模板。
 */
@Data
public class MessageConfig {

    /** 类别代号（与 TransferConfig 的类别代号对应） */
    private String categoryCode;

    /** 控制代号（与 TransferConfig 的控制代号对应） */
    private String controlCode;

    /** 通道类型：LOG / WEBHOOK */
    private String channelType;

    /** 目标地址：logger name 或 webhook URL */
    private String target;

    /** 消息模板，支持 {result}/{file}/{count} 等占位符 */
    private String messageTemplate;
}