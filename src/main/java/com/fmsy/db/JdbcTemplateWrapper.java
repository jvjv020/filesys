package com.fmsy.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

/**
 * JDBC模板封装类
 *
 * 封装 Spring JdbcTemplate,提供 {String, Object...} 与 {SqlStatement} 两种重载,
 * 是 Repository 层唯一的 JDBC 入口(业务代码不直接持有 JdbcTemplate)。
 */
public class JdbcTemplateWrapper {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTemplateWrapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 执行查询
     * @param sql SQL语句
     * @param rowMapper 结果映射器
     * @param params 参数
     * @return 查询结果列表
     */
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... params) {
        return jdbcTemplate.query(sql, rowMapper, params);
    }

    /**
     * 执行查询返回Map列表
     * @param sql SQL语句
     * @param params 参数
     * @return 每行结果为Map
     */
    public List<Map<String, Object>> queryForList(String sql, Object... params) {
        return jdbcTemplate.queryForList(sql, params);
    }

    /**
     * 执行查询返回单个对象
     * @param sql SQL语句
     * @param requiredType 返回类型
     * @param params 参数
     * @return 单个对象
     */
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... params) {
        return jdbcTemplate.queryForObject(sql, requiredType, params);
    }

    /**
     * 执行更新（INSERT/UPDATE/DELETE）
     * @param sql SQL语句
     * @param params 参数
     * @return 影响行数
     */
    public int update(String sql, Object... params) {
        return jdbcTemplate.update(sql, params);
    }

    /**
     * 批量更新
     * @param sql SQL语句
     * @param paramsList 参数列表（每元素是一个对象的参数数组）
     * @return 每个批次的 影响行数数组
     */
    public int[] batchUpdate(String sql, List<Object[]> paramsList) {
        return jdbcTemplate.batchUpdate(sql, paramsList);
    }

    /**
     * 执行参数化查询返回Map列表
     * @param stmt 参数化SQL语句
     * @return 每行结果为Map
     */
    public List<Map<String, Object>> queryForList(SqlStatement stmt) {
        return jdbcTemplate.queryForList(stmt.getSql(), stmt.getParamArray());
    }

    /**
     * 执行参数化查询返回单个对象
     * @param stmt 参数化SQL语句
     * @param requiredType 返回类型
     * @return 单个对象
     */
    public <T> T queryForObject(SqlStatement stmt, Class<T> requiredType) {
        return jdbcTemplate.queryForObject(stmt.getSql(), requiredType, stmt.getParamArray());
    }
}