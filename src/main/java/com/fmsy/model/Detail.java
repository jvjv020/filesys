package com.fmsy.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 明细实体类 - 对应明细表的一条记录
 *
 * 用途说明：
 * - DOWNLOAD_MULTI_NODE: 存储分桶的值，每个桶对应一个fieldValue
 * - 子命令处理时通过detail表获取分配的桶信息
 *
 * 字段说明：
 * - id: 自增主键
 * - commandId: 对应的命令ID
 * - categoryCode/controlCode: 类别/控制代号
 * - fieldValue: 拆分字段值（用于分桶）
 * - auditCount: 稽核数（该桶的预期记录数）
 * - status: 处理状态(ColumnNames.STATUS_*)
 */
@Data
public class Detail {
    private Long id;
    private Long commandId;
    private String categoryCode;
    private String controlCode;
    private String fieldValue;
    private Integer auditCount;
    private String status;
}