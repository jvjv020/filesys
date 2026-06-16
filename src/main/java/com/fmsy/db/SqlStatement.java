package com.fmsy.db;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL语句封装类 — 包含SQL语句和参数列表。
 *
 * <p>用于解决 WHERE 条件直接拼接导致的 SQL 注入风险。
 */
public class SqlStatement {
    private final String sql;
    private final List<Object> params;

    public SqlStatement(String sql, List<Object> params) {
        this.sql = sql;
        this.params = params != null ? new ArrayList<>(params) : new ArrayList<>();
    }

    public String getSql() {
        return sql;
    }

    public List<Object> getParams() {
        return params;
    }

    public Object[] getParamArray() {
        return params.toArray();
    }
}
