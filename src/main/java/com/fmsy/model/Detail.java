package com.fmsy.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 明细实体类 - 对应明细表的一条记录
 *
 * <p>用途说明：
 * <ul>
 *   <li>UPLOAD_MULTI + BATCH: 存储上传文件名和字段值</li>
 *   <li>DOWNLOAD_MULTI_NODE: 存储分桶信息,每个桶对应一个 specName</li>
 * </ul>
 *
 * <p>字段说明：
 * <ul>
 *   <li>id: 自增主键</li>
 *   <li>commandId: 对应的命令ID</li>
 *   <li>categoryCode/controlCode: 类别/控制代号</li>
 *   <li>fieldValue: 拆分字段值（用于分桶）或文件名上下文</li>
 *   <li>specName: 桶规格名,格式 "分区名|pkStart|pkEnd"（仅 DOWNLOAD_MULTI_NODE 使用）</li>
 *   <li>auditCount: 稽核数（该桶的预期记录数）</li>
 *   <li>status: 处理状态(ColumnNames.STATUS_*)</li>
 * </ul>
 */
@Data
public class Detail {
    private Long id;
    private Long commandId;
    private String categoryCode;
    private String controlCode;
    private String fieldValue;
    /** 桶规格名,格式 "分区名|pkStart|pkEnd"（仅 DOWNLOAD_MULTI_NODE Plan B 切分使用） */
    private String specName;
    private Integer auditCount;
    private String status;
}