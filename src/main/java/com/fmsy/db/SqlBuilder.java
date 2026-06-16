package com.fmsy.db;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL构建工具类
 *
 * <p>所有表名/列名走白名单正则校验,WHERE 条件走 ? 参数化绑定。
 * 配合 {@link JdbcTemplateWrapper} 使用。
 */
public class SqlBuilder {

    private static final String VALID_IDENTIFIER = "^[a-zA-Z_][a-zA-Z0-9_]*$";

    /**
     * 验证标识符(表名/列名)是否安全
     */
    private static boolean isValidIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }
        return identifier.matches(VALID_IDENTIFIER);
    }

    /**
     * 安全地拼接标识符列表
     */
    private static String joinIdentifiers(List<String> identifiers) {
        List<String> safe = new ArrayList<>();
        for (String id : identifiers) {
            if (!isValidIdentifier(id)) {
                throw new IllegalArgumentException("Invalid identifier: " + id);
            }
            safe.add(id);
        }
        return String.join(", ", safe);
    }

    /**
     * 把 {@code "col = ?"} 形式的条件列表追加到 {@code sql} 的 WHERE 子句,并把绑定值追加到 {@code params}。
     * 供 buildSelectParametric / buildCountParametric 共享,避免重复样板;conditions 空时 no-op。
     */
    private static void appendWhere(StringBuilder sql, List<Object> params,
                                    List<String> conditions, List<Object> conditionParams) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        sql.append(" WHERE ");
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) sql.append(" AND ");
            sql.append(conditions.get(i));
            if (conditionParams != null && i < conditionParams.size()) {
                params.add(conditionParams.get(i));
            }
        }
    }

    /**
     * 构建批量INSERT语句 — 手动拼多值 VALUES，不依赖驱动参数。
     *
     * <p>生成 {@code INSERT INTO table (f1, f2) VALUES (?, ?), (?, ?), ...}，
     * 配合 {@link TargetTableRepository#batchInsert} 把 {@code rows} 拍平后
     * 走单次 {@code update()} 完成一批写入，一次网络往返。
     *
     * @param table     表名
     * @param fields    字段名列表
     * @param batchSize 本次要插入的行数
     * @return INSERT INTO table (f1, f2) VALUES (?, ?), (?, ?), ...
     */
    public static String buildBatchInsert(String table, List<String> fields, int batchSize) {
        if (!isValidIdentifier(table)) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }
        String columns = joinIdentifiers(fields);
        String rowPlaceholders = "(" + String.join(", ", fields.stream().map(f -> "?").toList()) + ")";
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (").append(columns).append(") VALUES ");
        for (int i = 0; i < batchSize; i++) {
            if (i > 0) sb.append(", ");
            sb.append(rowPlaceholders);
        }
        return sb.toString();
    }

    // ==================== 参数化查询方法 ====================

    /**
     * 构建参数化SELECT语句
     * @param table 表名(必须是受信任的标识符)
     * @param fields 要查询的字段(为空则SELECT *)
     * @param distinct 是否在SELECT中追加DISTINCT关键字
     * @param conditions 条件列表,每项为"column = ?"格式
     * @param conditionParams 条件的参数值
     * @param orderBy ORDER BY字段列表(每个元素经过isValidIdentifier校验后以逗号拼接;null/空 → 无ORDER BY)
     * @param limit LIMIT值
     * @param offset OFFSET值
     * @return SqlStatement包含SQL和参数列表
     */
    public static SqlStatement buildSelectParametric(String table, List<String> fields, boolean distinct,
            List<String> conditions, List<Object> conditionParams,
            List<String> orderBy, Integer limit, Integer offset) {
        if (!isValidIdentifier(table)) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }
        String columns = fields.isEmpty() ? "*" : joinIdentifiers(fields);
        StringBuilder sql = new StringBuilder("SELECT ");
        if (distinct) {
            sql.append("DISTINCT ");
        }
        sql.append(columns).append(" FROM ").append(table);
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, conditions, conditionParams);
        if (orderBy != null && !orderBy.isEmpty()) {
            sql.append(" ORDER BY ").append(joinIdentifiers(orderBy));
        }
        if (limit != null) {
            sql.append(" LIMIT ").append(limit);
        }
        if (offset != null) {
            sql.append(" OFFSET ").append(offset);
        }
        return new SqlStatement(sql.toString(), params);
    }

    /**
     * 构建无WHERE条件的DELETE语句(清空表)
     * @param table 表名(必须是受信任的标识符)
     * @return DELETE FROM table
     */
    public static String buildDeleteAll(String table) {
        if (!isValidIdentifier(table)) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }
        return "DELETE FROM " + table;
    }

    /**
     * 构建参数化COUNT语句
     * @param table 表名(必须是受信任的标识符)
     * @param conditions 条件列表,每项为"column = ?"格式
     * @param conditionParams 条件的参数值
     * @return SqlStatement包含SQL和参数列表
     */
    public static SqlStatement buildCountParametric(String table, List<String> conditions, List<Object> conditionParams) {
        if (!isValidIdentifier(table)) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(table);
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, conditions, conditionParams);
        return new SqlStatement(sql.toString(), params);
    }
}
