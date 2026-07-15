package com.fmsy.db;

import com.fmsy.config.DataSourceConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库表元数据读取服务
 *
 * <p>功能说明：
 * <ul>
 *   <li>从 information_schema.columns 读取表字段(按 ordinal_position 顺序)</li>
 *   <li>用于 FieldMappingBuilder 构建 tableFields 顺序映射</li>
 *   <li>内置 {@link ConcurrentHashMap} 缓存，避免多文件/多桶场景下重复查询同一张表的元数据</li>
 *   <li>支持 PostgreSQL / GaussDB(共享 information_schema 规范)</li>
 * </ul>
 *
 * <p>缓存说明：表元数据在应用运行期间不变（DDL 变更需要重启应用），
 * 因此缓存无 TTL 过期策略。key = {@code dbName + "|" + tableName.toLowerCase()}。
 * 如需清空缓存（如测试），可调用 {@link #clearCache()}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableMetadataService {

    /** 表元数据缓存：key = dbName + "|" + tableName.toLowerCase()，value = 列名列表 */
    private final ConcurrentHashMap<String, List<String>> columnCache = new ConcurrentHashMap<>();

    private final DataSourceConfig.DbPool dbPool;

    /**
     * 读取目标表的所有列名，按表定义顺序返回。
     *
     * <p>内部使用缓存：同一表第二次查询直接返回缓存结果，不再查询 DB。
     *
     * @param dbName    数据库连接池 ID，允许为 null/空白(回退到 DB_DEFAULT)
     * @param tableName 表名
     * @return 列名列表(去除未识别或异常情况下返回空列表)
     */
    public List<String> getTableColumns(String dbName, String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            log.warn("getTableColumns skipped: empty tableName");
            return List.of();
        }
        String cacheKey = buildCacheKey(dbName, tableName);
        return columnCache.computeIfAbsent(cacheKey, key -> loadColumns(dbName, tableName));
    }

    /**
     * 清空缓存 — 仅用于测试或运维场景（如动态 DDL 后需重新加载元数据）。
     */
    public void clearCache() {
        int size = columnCache.size();
        columnCache.clear();
        log.info("Table metadata cache cleared ({} entries)", size);
    }

    /**
     * 从 DB 加载表字段列表。
     */
    private List<String> loadColumns(String dbName, String tableName) {
        JdbcTemplateWrapper jdbc = resolveJdbc(dbName);
        String sql = "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = ? ORDER BY ordinal_position";
        try {
            List<String> columns = jdbc.queryForList(sql, tableName.toLowerCase(Locale.ROOT))
                    .stream()
                    .map(row -> row.get("column_name"))
                    .filter(v -> v != null)
                    .map(Object::toString)
                    .toList();
            log.debug("Loaded {} columns for table {} (db={})", columns.size(), tableName, dbName);
            return columns;
        } catch (Exception e) {
            log.error("Failed to read columns for table {} from db {}: {}",
                    tableName, dbName, e.getMessage());
            return List.of();
        }
    }

    private static String buildCacheKey(String dbName, String tableName) {
        return (dbName != null ? dbName : "") + "|" + tableName.toLowerCase(Locale.ROOT);
    }

    private JdbcTemplateWrapper resolveJdbc(String dbName) {
        return dbPool.resolveJdbcTemplate(dbName);
    }
}
