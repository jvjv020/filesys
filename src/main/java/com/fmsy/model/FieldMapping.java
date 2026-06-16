package com.fmsy.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 字段映射配置
 *
 * 功能说明：
 * - 建立数据库字段和文件字段之间的映射关系
 * - 处理字段名不一致的情况
 * - 提供统一的字段值获取方法
 *
 * 映射类型：
 * - tableFields: 数据库表字段列表
 * - extraFields: 额外字段(固定值)
 */
@Data
public class FieldMapping {
    private com.fmsy.model.TransferConfig config;
    /** 数据库表字段列表 */
    private List<String> tableFields;
    /** 额外字段映射(字段名→固定值) */
    private Map<String, String> extraFields;

    /**
     * 获取字段值
     * 优先级：record中的值 > extraFields中的值
     */
    public Object getValue(Map<String, Object> record, String fieldName) {
        if (record.containsKey(fieldName)) {
            return record.get(fieldName);
        }
        if (extraFields != null && extraFields.containsKey(fieldName)) {
            return extraFields.get(fieldName);
        }
        return null;
    }
}
