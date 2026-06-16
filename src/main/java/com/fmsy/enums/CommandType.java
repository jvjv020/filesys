package com.fmsy.enums;

/**
 * 命令类型枚举
 *
 * 类型说明：
 * - SERIAL(null): 串行命令，相同类别+控制代号必须在节点间串行处理
 * - BATCH("R"): 批量命令，使用明细表的指名字段值或文件名定位
 * - COORDINATED("S"): 协调命令，由主命令创建，用于多节点桶分发
 * - TEMPORARY("T"): 临时命令，携带内联临时配置（temp_config），无需查传输配置表
 *
 * 数据库存储：
 * - SERIAL: null（空）
 * - BATCH: 'R'
 * - COORDINATED: 'S'
 * - TEMPORARY: 'T'
 */
public enum CommandType {
    SERIAL(null),       // 串行（空）
    BATCH("R"),          // 批量
    COORDINATED("S"),   // 协调

    TEMPORARY("T");      // 临时（内联配置）

    private final String code;

    CommandType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 根据代码获取命令类型 */
    public static CommandType fromCode(String code) {
        if (code == null) {
            return SERIAL;
        }
        for (CommandType type : values()) {
            if (code.equals(type.code)) {
                return type;
            }
        }
        return SERIAL;
    }
}