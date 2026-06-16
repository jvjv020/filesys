package com.fmsy.db;

import com.fmsy.config.DataSourceConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 数据库表元数据读取服务
 *
 * 功能说明：
 * - 从 information_schema.columns 读取表字段(按 ordinal_position 顺序)
 * - 用于 FieldMappingBuilder 构建 tableFields 顺序映射
 * - 支持 PostgreSQL / GaussDB(共享 information_schema 规范)
 *
 * 使用场景：
 * - 上传/下发前读取目标表元数据,按表定义顺序生成字段列表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableMetadataService {

    private final DataSourceConfig.DbPool dbPool;

    /**
     * 读取目标表的所有列名,按表定义顺序返回。
     *
     * @param dbName    数据库连接池 ID,允许为 null/空白(回退到 DB_DEFAULT)
     * @param tableName 表名
     * @return 列名列表(去除未识别或异常情况下返回空列表)
     */
    public List<String> getTableColumns(String dbName, String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            log.warn("getTableColumns skipped: empty tableName");
            return List.of();
        }
        JdbcTemplateWrapper jdbc = resolveJdbc(dbName);
        String sql = "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = ? ORDER BY ordinal_position";
        try {
            return jdbc.queryForList(sql, tableName.toLowerCase(Locale.ROOT))
                    .stream()
                    .map(row -> row.get("column_name"))
                    .filter(v -> v != null)
                    .map(Object::toString)
                    .toList();
        } catch (Exception e) {
            log.error("Failed to read columns for table {} from db {}: {}",
                    tableName, dbName, e.getMessage());
            return List.of();
        }
    }

    private JdbcTemplateWrapper resolveJdbc(String dbName) {
        return dbPool.resolveJdbcTemplate(dbName);
    }
}
