package com.fmsy.db;

import com.fmsy.config.DataSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分区表检测与分区顺序查询工具。
 *
 * <p>用于优化分区表的查询性能 — 按分区顺序逐个查询而非跨分区全表扫描,
 * 利用分区剪枝减少扫描范围。所有查询方法返回结果与原始查询等价,
 * 调用方无需感知分区逻辑。
 *
 * <p>分区信息缓存在内存中(5 分钟 TTL),避免每次查询都访问元数据表。
 *
 * <p>兼容 PostgreSQL / GaussDB 的分区表实现(均基于 pg_catalog.pg_inherits)。
 */
@Slf4j
@Service
public class PartitionHelper {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private static final String SQL_CHECK_PARTITIONS =
            "SELECT c.relname AS partition_name " +
            "FROM pg_catalog.pg_class c " +
            "JOIN pg_catalog.pg_inherits i ON c.oid = i.inhrelid " +
            "WHERE i.inhparent = (SELECT oid FROM pg_catalog.pg_class WHERE relname = ?) " +
            "ORDER BY c.relname";

    private static final String SQL_PRIMARY_KEY =
            "SELECT kcu.column_name " +
            "FROM information_schema.table_constraints tc " +
            "JOIN information_schema.key_column_usage kcu " +
            "  ON tc.constraint_catalog = kcu.constraint_catalog " +
            "  AND tc.constraint_schema = kcu.constraint_schema " +
            "  AND tc.constraint_name = kcu.constraint_name " +
            "WHERE tc.table_name = ? " +
            "  AND tc.constraint_type = 'PRIMARY KEY' " +
            "ORDER BY kcu.ordinal_position";

    private final DataSourceConfig.DbPool dbPool;
    private final ConcurrentHashMap<String, PartitionInfo> cache = new ConcurrentHashMap<>();

    public PartitionHelper(DataSourceConfig.DbPool dbPool) {
        this.dbPool = dbPool;
    }

    // ==================== 公开方法 ====================

    /**
     * 判断目标表是否为分区表。
     *
     * @param dbName    数据库配置名(允许 null/空白 → 回退到默认 DB)
     * @param tableName 表名
     * @return true = 分区表,false = 非分区表或查询失败
     */
    public boolean isPartitioned(String dbName, String tableName) {
        return getPartitionInfo(dbName, tableName).partitioned;
    }

    /**
     * 获取分区子表列表(已按名称排序)。
     *
     * @param dbName    数据库配置名
     * @param tableName 表名
     * @return 分区名列表(非分区表返回空列表)
     */
    public List<String> getPartitions(String dbName, String tableName) {
        return getPartitionInfo(dbName, tableName).partitions;
    }

    /**
     * 按分区顺序执行 DISTINCT 查询并合并去重结果。
     *
     * <p>适用于 {@code SELECT DISTINCT fields FROM tableName ORDER BY fields} 场景。
     * 每个分区单独查询 DISTINCT,然后将结果合并去重(各分区结果集极小,合并开销可忽略)。
     *
     * @param dbName    数据库配置名
     * @param tableName 表名(仅用于 fallback;分区查询使用子表名)
     * @param fields    要查询的字段
     * @param orderBy   ORDER BY 字段
     * @return 合并后的 DISTINCT 结果,等价于对全表做 DISTINCT 查询
     */
    public List<Map<String, Object>> scanPartitionsDistinct(String dbName, String tableName,
                                                             List<String> fields, List<String> orderBy) {
        List<String> partitions = getPartitions(dbName, tableName);
        if (partitions.isEmpty()) {
            return List.of();
        }

        JdbcTemplateWrapper jdbc = resolveJdbc(dbName);
        List<Map<String, Object>> merged = new ArrayList<>();

        for (String partition : partitions) {
            try {
                SqlStatement stmt = SqlBuilder.buildSelectParametric(
                        partition, fields, true, List.of(), List.of(), orderBy, null, null);
                List<Map<String, Object>> rows = jdbc.queryForList(stmt);
                for (Map<String, Object> row : rows) {
                    if (!containsRow(merged, row)) {
                        merged.add(row);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to query partition {} of table {}: {}. Falling back to full-table query",
                        partition, tableName, e.getMessage());
                return null; // 通知调用方回退到原始查询
            }
        }

        log.debug("Scanned {} partitions for DISTINCT on {}, got {} distinct values",
                partitions.size(), tableName, merged.size());
        return merged;
    }

    /**
     * 获取目标表的主键列列表(已按 ordinal_position 排序)。
     *
     * @param dbName    数据库配置名
     * @param tableName 表名
     * @return 主键列名列表(无主键返回空列表)
     */
    public List<String> getPrimaryKeyColumns(String dbName, String tableName) {
        return getPartitionInfo(dbName, tableName).primaryKeyColumns;
    }

    // ==================== 内部方法 ====================

    /**
     * 获取分区信息(缓存命中直接返回,否则查询 DB)。
     */
    private PartitionInfo getPartitionInfo(String dbName, String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return PartitionInfo.NOT_PARTITIONED;
        }

        String key = buildCacheKey(dbName, tableName);
        PartitionInfo info = cache.get(key);
        if (info != null && !info.isExpired()) {
            return info;
        }

        info = loadPartitionInfo(dbName, tableName);
        cache.put(key, info);
        return info;
    }

    /**
     * 从数据库查询分区信息及主键信息。
     * 查询失败时返回 NOT_PARTITIONED(静默降级)。
     */
    private PartitionInfo loadPartitionInfo(String dbName, String tableName) {
        try {
            JdbcTemplateWrapper jdbc = resolveJdbc(dbName);
            String tableLower = tableName.toLowerCase();

            // 查询分区信息
            List<Map<String, Object>> rows = jdbc.queryForList(SQL_CHECK_PARTITIONS, tableLower);
            List<String> partitions = new ArrayList<>();
            if (!rows.isEmpty()) {
                for (Map<String, Object> row : rows) {
                    Object name = row.get("partition_name");
                    if (name != null) {
                        partitions.add(name.toString());
                    }
                }
            }

            // 查询主键信息(分区表和普通表都适用)
            List<String> pkColumns = loadPrimaryKeyColumns(jdbc, tableLower);

            boolean isPartitioned = !partitions.isEmpty();
            log.info("Table {}: partitioned={}, partitions={}, primaryKey={}",
                    tableName, isPartitioned, partitions, pkColumns);

            return new PartitionInfo(isPartitioned,
                    isPartitioned ? Collections.unmodifiableList(partitions) : List.of(),
                    Collections.unmodifiableList(pkColumns),
                    System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("Table {} metadata unavailable: {}", tableName, e.getMessage());
            return PartitionInfo.NOT_PARTITIONED;
        }
    }

    /**
     * 查询主键列。
     */
    private List<String> loadPrimaryKeyColumns(JdbcTemplateWrapper jdbc, String tableLower) {
        try {
            return jdbc.queryForList(SQL_PRIMARY_KEY, tableLower)
                    .stream()
                    .map(row -> row.get("column_name"))
                    .filter(v -> v != null)
                    .map(Object::toString)
                    .toList();
        } catch (Exception e) {
            log.debug("Failed to load primary key for table {}: {}", tableLower, e.getMessage());
            return List.of();
        }
    }

    private static String buildCacheKey(String dbName, String tableName) {
        String db = (dbName == null || dbName.isEmpty()) ? "" : dbName;
        return db + "|" + tableName.toLowerCase();
    }

    private JdbcTemplateWrapper resolveJdbc(String dbName) {
        return dbPool.resolveJdbcTemplate(dbName);
    }

    /**
     * 判断 merged 列表中是否已包含与 row 具有相同值的行(按所有键比较)。
     */
    private static boolean containsRow(List<Map<String, Object>> merged, Map<String, Object> row) {
        for (Map<String, Object> existing : merged) {
            if (mapsEqual(existing, row)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mapsEqual(Map<String, Object> a, Map<String, Object> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, Object> entry : a.entrySet()) {
            Object va = entry.getValue();
            Object vb = b.get(entry.getKey());
            if (va == null ? vb != null : !va.equals(vb)) {
                return false;
            }
        }
        return true;
    }

    // ==================== 内部类型 ====================

    /**
     * 分区信息缓存条目。
     */
    static class PartitionInfo {
        static final PartitionInfo NOT_PARTITIONED =
                new PartitionInfo(false, List.of(), List.of(), 0L);

        final boolean partitioned;
        final List<String> partitions;
        final List<String> primaryKeyColumns;
        final long cachedAt;

        PartitionInfo(boolean partitioned, List<String> partitions, List<String> primaryKeyColumns, long cachedAt) {
            this.partitioned = partitioned;
            this.partitions = partitions;
            this.primaryKeyColumns = primaryKeyColumns;
            this.cachedAt = cachedAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MS;
        }
    }
}
