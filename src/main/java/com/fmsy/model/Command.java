package com.fmsy.model;

import com.fmsy.enums.CommandType;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 命令实体类 - 代表数据库命令表的一条记录
 *
 * 字段说明：
 * - id: 命令自增主键
 * - categoryCode: 类别代号，用于匹配传输配置
 * - controlCode: 控制代号，用于匹配传输配置
 * - commandType: 命令类型（SERIAL串行/BATCH批量/COORDINATED协调）
 * - auditCount: 稽核数，用于预审计和后审计的记录数验证
 * - extraInfo: 额外信息，存储扩展参数（如主命令ID用于S型子命令）
 * - startTime: 处理开始时间
 * - nodeId: 处理节点ID
 * - status: 处理状态(ColumnNames.STATUS_*)
 *
 * 命令类型详解：
 * - null (SERIAL): 相同类别+控制代号的命令必须在各节点间串行处理
 * - 'R' (BATCH): 使用明细表的指名字段值或文件名来定位文件
 * - 'S' (COORDINATED): 由主命令创建的子命令，用于多节点桶分发
 */
@Data
public class Command {
    private Long id;
    private String categoryCode;
    private String controlCode;
    private CommandType commandType;
    private Integer auditCount;
    private String extraInfo;

    /** 临时指令 JSON 配置（仅指令类型='T' 时有值，存于 temp_config 列） */
    private String tempConfig;

    private LocalDateTime startTime;
    private String status;

    /**
     * 若 startTime 尚未设置,填充为当前时间(用于结果表写真正的处理起始时间)。
     * 与 {@code transfer.TransferService.process} 与 {@code transfer.download.ChildBucketProcessor.pollAndProcess} 协作:
     * 业务方在派发成功后、ResultBuilder 构造前调用,保证 startTime 与"竞争成功时刻"对齐。
     */
    public void markStartTimeIfAbsent() {
        if (startTime == null) {
            startTime = LocalDateTime.now();
        }
    }
}