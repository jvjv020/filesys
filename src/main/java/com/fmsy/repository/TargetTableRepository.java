package com.fmsy.repository;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.db.PartitionHelper;
import com.fmsy.db.SqlBuilder;
import com.fmsy.db.SqlStatement;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 目标业务表 Repository — 集中管理对**配置指定的目标表**的 SQL 访问。
 *
 * <p>目标表在每次传输时由 TransferConfig.dbName + tableName 决定(多数据源 + 动态表名),
 * 区别于其他四个静态表 Repository,因此需要 dbName + tableName 双重入参。
 *
 * <p>覆盖来源:
 * <ul>
 *   <li>{@code transfer/upload/UploadSupport.insertAndVerifyPerFileInTx} — batchInsert + postAudit</li>
 *   <li>{@code transfer/upload/{Single,MultiDirectory,MultiBatch}UploadHandler} — truncate / count(后审计)</li>
 *   <li>{@code transfer/download/{Single,SingleNode,MultiNode}DownloadHandler} — distinct / streamQuery / count</li>
 *   <li>{@code audit/AuditService} — preAuditDbRecords / postAuditCount / countByBucket</li>
 * </ul>
 *
 * <p>安全说明:表名必须经过 {@link SqlBuilder} 的 isValidIdentifier 校验,
 * 所有用户可控输入走 ? 参数化绑定;此 Repository 拒绝非标识符表名。
 */
@Slf4j
@Repository
public class TargetTableRepository {

    private final DataSourceConfig.DbPool dbPool;
    private final PartitionHelper partitionHelper;

    public TargetTableRepository(DataSourceConfig.DbPool dbPool, PartitionHelper partitionHelper) {
        this.dbPool = dbPool;
        this.partitionHelper = partitionHelper;
    }

    private JdbcTemplateWrapper getJdbc(String dbName) {
        return dbPool.resolveJdbcTemplate(dbName);
    }

    private DataSource getDataSource(String dbName) {
        String resolved = (dbName == null || dbName.isEmpty()) ? ColumnNames.DEFAULT_DB : dbName;
        DataSource dataSource = dbPool.getDataSource(resolved);
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource not found: " + resolved);
        }
        return dataSource;
    }

    // ==================== 清空表 ====================

    /**
     * 清空目标表。
     * 走 buildDeleteAll 内部已做 isValidIdentifier 校验。
     */
    public void truncate(String dbName, String tableName) {
        String sql = SqlBuilder.buildDeleteAll(tableName);
        getJdbc(dbName).update(sql);
        log.debug("Truncated target table: {}.{}", dbName, tableName);
    }

    // ==================== 批量插入 ====================

    /**
     * 批量插入到目标表。
     *
     * <p>手动拼多值 INSERT + 拍平参数走单次 {@code update()}，
     * 一次网络往返完成整批写入，不依赖驱动 {@code reWriteBatchedInserts} 参数。
     *
     * <p>每行字段顺序必须与 fields 列表一致；rows 是已切好的批量，
     * 本方法把 rows 拍平为单个参数数组后执行单条多值 SQL。
     */
    public void batchInsert(String dbName, String tableName, List<String> fields, List<Object[]> rows) {
        if (fields == null || fields.isEmpty() || rows == null || rows.isEmpty()) {
            return;
        }
        String sql = SqlBuilder.buildBatchInsert(tableName, fields, rows.size());
        // 把 List<Object[]> 拍平为单个 Object[] 匹配多值占位符
        Object[] flatParams = new Object[fields.size() * rows.size()];
        int idx = 0;
        for (Object[] row : rows) {
            System.arraycopy(row, 0, flatParams, idx, row.length);
            idx += row.length;
        }
        getJdbc(dbName).update(sql, flatParams);
        log.debug("Batch inserted {} row(s) into {}.{}", rows.size(), dbName, tableName);
    }

    // ==================== 简单 COUNT ====================

    /**
     * 全表 COUNT(行为同 AuditService.preAuditDbRecords / postAuditDownload / UploadSupport.postAudit)。
     * 返回 0 当结果为 null(空表)。
     */
    public int count(String dbName, String tableName) {
        SqlStatement stmt = SqlBuilder.buildCountParametric(tableName, List.of(), List.of());
        Integer count = getJdbc(dbName).queryForObject(stmt, Integer.class);
        return count != null ? count : 0;
    }

    // ==================== 桶级别 COUNT ====================

    /**
     * 按桶筛选 COUNT(支持多字段 splitField,例 {@code "REGION,STATUS"} + {@code "EAST,ACTIVE"})。
     * 行为同 AuditService.preAuditByBucket。
     */
    public int countByBucket(String dbName, String tableName, String splitField, String fieldValue) {
        if (splitField == null || splitField.isEmpty() || fieldValue == null || fieldValue.isEmpty()) {
            return count(dbName, tableName);
        }
        List<Object> params = new ArrayList<>();
        List<String> predicates = buildBucketPredicates(splitField, fieldValue, params);
        SqlStatement stmt = SqlBuilder.buildCountParametric(tableName, predicates, params);
        Integer count = getJdbc(dbName).queryForObject(stmt, Integer.class);
        return count != null ? count : 0;
    }

    // ==================== 查询 ====================

    /**
     * 参数化 SELECT(只用于 DISTINCT / COUNT 这类小结果查询;大数据文件生成走 streamQueryBatches)。
     *
     * @param dbName     数据库配置名
     * @param tableName  目标表
     * @param fields     要查询的字段(null/空 → SELECT *)
     * @param distinct   是否追加 DISTINCT 关键字
     * @param predicates WHERE 谓词(每项"col = ?"格式)
     * @param params     谓词绑定参数
     * @param orderBy    ORDER BY 字段列表(每个走 isValidIdentifier)
     * @param limit      LIMIT 值(null 表示无)
     */
    public List<Map<String, Object>> querySmallResult(String dbName, String tableName,
                                                      List<String> fields, boolean distinct,
                                                      List<String> predicates, List<Object> params,
                                                      List<String> orderBy, Integer limit) {
        // 分区表 DISTINCT 优化:按分区顺序逐个查询,利用分区剪枝减少扫描范围
        if (distinct && (predicates == null || predicates.isEmpty())) {
            List<Map<String, Object>> partitioned = tryPartitionDistinct(dbName, tableName, fields, orderBy);
            if (partitioned != null) return partitioned;
        }
        SqlStatement stmt = SqlBuilder.buildSelectParametric(
                tableName, listOrEmpty(fields), distinct, listOrEmpty(predicates), listOrEmpty(params), listOrEmpty(orderBy), limit, null);
        return getJdbc(dbName).queryForList(stmt);
    }

    /**
     * 尝试按分区顺序执行 DISTINCT 查询。
     *
     * @return 分区查询结果,或 null(非分区表/查询失败 → 调用方回退到原始全表查询)
     */
    private List<Map<String, Object>> tryPartitionDistinct(String dbName, String tableName,
                                                            List<String> fields, List<String> orderBy) {
        if (!partitionHelper.isPartitioned(dbName, tableName)) {
            return null;
        }
        List<Map<String, Object>> result = partitionHelper.scanPartitionsDistinct(
                dbName, tableName, listOrEmpty(fields), listOrEmpty(orderBy));
        if (result == null) {
            log.warn("Partition scan returned null for {}, falling back to full-table query", tableName);
        }
        return result; // null = fallback
    }

    /**
     * 当调用方未指定 ORDER BY 时,自动降级为主键排序以利用索引加速。
     * 如果表有主键且 orderBy 为 null 或空,返回主键列;否则返回原 orderBy。
     */
    private List<String> resolveOrderByWithPkFallback(String dbName, String tableName, List<String> orderBy) {
        if (orderBy != null && !orderBy.isEmpty()) {
            return orderBy;
        }
        List<String> pk = partitionHelper.getPrimaryKeyColumns(dbName, tableName);
        if (!pk.isEmpty()) {
            log.debug("Auto-appending ORDER BY primary key {} for table {}", pk, tableName);
        }
        return pk;
    }

    /**
     * 尝试按分区顺序执行流式查询。
     *
     * @return 分区顺序流,或 null(非分区表/查询失败 → 调用方回退)
     */
    private DataStream tryPartitionSequential(String dbName, String tableName,
                                               List<String> fields, boolean distinct,
                                               List<String> orderBy, Integer limit) {
        if (!partitionHelper.isPartitioned(dbName, tableName)) {
            return null;
        }
        List<String> partitions = partitionHelper.getPartitions(dbName, tableName);
        if (partitions.isEmpty()) {
            return null;
        }
        List<String> resolvedOrderBy = resolveOrderByWithPkFallback(dbName, tableName, orderBy);
        String baseSql = buildSelectSql(listOrEmpty(fields), distinct, List.of(), resolvedOrderBy, limit);
        return new PartitionSequentialIterator(
                getDataSource(dbName), partitions, baseSql, List.of(), SystemConstants.DEFAULT_BATCH_SIZE, tableName);
    }

    /**
     * 构建参数化 SELECT 语句的 SQL 模板(表名使用 {@code __TABLE__} 占位,
     * 由 {@link PartitionSequentialIterator} 在创建每个分区查询时替换为实际分区名)。
     */
    private static String buildSelectSql(List<String> fields, boolean distinct,
                                          List<String> predicates, List<String> orderBy, Integer limit) {
        StringBuilder sql = new StringBuilder("SELECT ");
        if (distinct) {
            sql.append("DISTINCT ");
        }
        if (fields.isEmpty()) {
            sql.append("*");
        } else {
            sql.append(String.join(", ", fields));
        }
        sql.append(" FROM __TABLE__");
        if (!predicates.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
        if (orderBy != null && !orderBy.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", orderBy));
        }
        if (limit != null) {
            sql.append(" LIMIT ").append(limit);
        }
        return sql.toString();
    }

    public DataStream streamQueryBatches(String dbName, String tableName,
                                          List<String> fields, boolean distinct,
                                          List<String> predicates, List<Object> params,
                                          List<String> orderBy, Integer limit) {
        // 分区表流式优化:按分区顺序逐个读取
        if (predicates == null || predicates.isEmpty()) {
            DataStream partitioned = tryPartitionSequential(dbName, tableName, fields, distinct, orderBy, limit);
            if (partitioned != null) return partitioned;
        }
        // 非分区表:未指定 ORDER BY 时自动追加主键排序,利用索引加速
        List<String> resolvedOrderBy = resolveOrderByWithPkFallback(dbName, tableName, orderBy);
        SqlStatement stmt = SqlBuilder.buildSelectParametric(
                tableName, listOrEmpty(fields), distinct, listOrEmpty(predicates), listOrEmpty(params), resolvedOrderBy, limit, null);
        return new StreamingQuery(getDataSource(dbName), stmt, SystemConstants.DEFAULT_BATCH_SIZE, tableName);
    }

    // ==================== 桶数据查询 ====================

    /**
     * 直通流式查询 — 不做任何分区检测,直接对指定表执行 SELECT * ORDER BY pk。
     * 专供 {@link ParallelFileGenerator} 读取分区子表使用。
     *
     * @param dbName    数据库配置名
     * @param tableName 表名(分区子表名,已确认非分区表)
     * @param orderBy   ORDER BY 字段(通常为主键,从父表获取)
     */
    public DataStream streamTableDirect(String dbName, String tableName, List<String> orderBy) {
        SqlStatement stmt = SqlBuilder.buildSelectParametric(
                tableName, List.of(), false, List.of(), List.of(), listOrEmpty(orderBy), null, null);
        return new StreamingQuery(getDataSource(dbName), stmt, SystemConstants.DEFAULT_BATCH_SIZE, tableName);
    }

    /**
     * 按桶字段值流式取目标表数据。
     * 单字段场景:行为 {@code SELECT * FROM tableName WHERE col = ?}。
     */
    public DataStream streamBucketData(String dbName, String tableName,
                                        String splitField, Object fieldValue) {
        if (splitField == null || splitField.isEmpty()) {
            throw new IllegalArgumentException("splitField is empty for table " + tableName);
        }
        // 自动追加主键排序(如果未显式指定),利用索引加速
        List<String> orderBy = resolveOrderByWithPkFallback(dbName, tableName, null);

        // 分区表桶数据优化:按分区顺序逐个查询,每个分区只扫描自己的数据
        if (partitionHelper.isPartitioned(dbName, tableName)) {
            List<String> partitions = partitionHelper.getPartitions(dbName, tableName);
            if (!partitions.isEmpty()) {
                List<Object> params = new ArrayList<>();
                List<String> predicates = buildBucketPredicates(splitField, String.valueOf(fieldValue), params);
                String baseSql = buildSelectSql(List.of(), false, predicates, orderBy, null);
                return new PartitionSequentialIterator(getDataSource(dbName), partitions,
                        baseSql, params, SystemConstants.DEFAULT_BATCH_SIZE, tableName);
            }
        }
        List<Object> params = new ArrayList<>();
        List<String> predicates = buildBucketPredicates(splitField, String.valueOf(fieldValue), params);
        SqlStatement stmt = SqlBuilder.buildSelectParametric(
                tableName, List.of(), false, predicates, params, orderBy, null, null);
        return new StreamingQuery(getDataSource(dbName), stmt, SystemConstants.DEFAULT_BATCH_SIZE, tableName);
    }

    private static List<String> buildBucketPredicates(String splitField, String fieldValue, List<Object> params) {
        String[] fieldNames = splitField.split(",");
        String[] fieldValues = fieldValue.split(",");
        if (fieldNames.length != fieldValues.length) {
            throw new IllegalArgumentException("splitField and fieldValue count mismatch: " + splitField + " / " + fieldValue);
        }
        List<String> predicates = new ArrayList<>();
        for (int i = 0; i < fieldNames.length; i++) {
            String fieldName = fieldNames[i].trim();
            if (fieldName.isEmpty()) {
                throw new IllegalArgumentException("splitField contains empty field: " + splitField);
            }
            predicates.add(fieldName + " = ?");
            params.add(fieldValues[i].trim());
        }
        return predicates;
    }

    private static <T> List<T> listOrEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    // ==================== PK 范围流式查询(Plan B 分桶) ====================

    /**
     * 按 PK 范围流式查询 — 从 pkStart 到 pkEnd（不含）之间按 PK 序读取数据。
     *
     * <p>返回 {@link DataStream}，调用方用 try-with-resources 自动关闭。
     *
     * @param dbName    数据库配置名
     * @param tableName 表名
     * @param pkColumns 主键列名列表
     * @param pkStart   起始 PK 值列表（null 表示从头开始）
     * @param pkEnd     结束 PK 值列表（null 表示直到末尾）
     */
    public DataStream streamByPkRange(String dbName, String tableName,
                                       List<String> pkColumns,
                                       List<Object> pkStart, List<Object> pkEnd) {
        List<String> predicates = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        String orderByClause = String.join(", ", pkColumns);

        if (pkStart != null && !pkStart.isEmpty()) {
            // 使用行值语法: (pk1, pk2, ...) >= (?, ?, ...)
            StringBuilder tuple = new StringBuilder("(");
            tuple.append(String.join(", ", pkColumns));
            tuple.append(") >= (");
            for (int i = 0; i < pkStart.size(); i++) {
                if (i > 0) tuple.append(", ");
                tuple.append("?");
            }
            tuple.append(")");
            predicates.add(tuple.toString());
            params.addAll(pkStart);
        }
        if (pkEnd != null && !pkEnd.isEmpty()) {
            StringBuilder tuple = new StringBuilder("(");
            tuple.append(String.join(", ", pkColumns));
            tuple.append(") < (");
            for (int i = 0; i < pkEnd.size(); i++) {
                if (i > 0) tuple.append(", ");
                tuple.append("?");
            }
            tuple.append(")");
            predicates.add(tuple.toString());
            params.addAll(pkEnd);
        }

        SqlStatement stmt = SqlBuilder.buildSelectParametric(
                tableName, List.of(), false, predicates, params,
                List.of(orderByClause.split(", ")), null, null);
        return new StreamingQuery(getDataSource(dbName), stmt,
                SystemConstants.DEFAULT_BATCH_SIZE, tableName);
    }

    /**
     * 查询 PK 边界 — 返回 pkStart 之后第 offset 行的完整记录。
     *
     * <p>用于 Plan B 切分:逐块跳过 offset 行找到桶的结束边界。
     *
     * @param dbName      数据库配置名
     * @param tableName   表名
     * @param pkColumns   主键列名列表
     * @param afterPk     起始 PK 值列表（null 从首行开始）
     * @param offset      跳过行数
     * @param extraWhere  额外 WHERE 条件（可为 null）
     * @param extraParams 额外 WHERE 参数（可为 null）
     * @return 边界行的完整记录；无更多行时返回 null
     */
    public Map<String, Object> queryNextPkBoundary(String dbName, String tableName,
                                                    List<String> pkColumns,
                                                    List<Object> afterPk, int offset,
                                                    String extraWhere, List<Object> extraParams) {
        List<String> predicates = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        String orderByClause = String.join(", ", pkColumns);

        if (afterPk != null && !afterPk.isEmpty()) {
            StringBuilder tuple = new StringBuilder("(");
            tuple.append(String.join(", ", pkColumns));
            tuple.append(") >= (");
            for (int i = 0; i < afterPk.size(); i++) {
                if (i > 0) tuple.append(", ");
                tuple.append("?");
            }
            tuple.append(")");
            predicates.add(tuple.toString());
            params.addAll(afterPk);
        }
        if (extraWhere != null && !extraWhere.isEmpty()) {
            predicates.add(extraWhere);
            if (extraParams != null) {
                params.addAll(extraParams);
            }
        }

        SqlStatement stmt = SqlBuilder.buildSelectParametric(
                tableName, List.of(), false, predicates, params,
                List.of(orderByClause.split(", ")), 1, offset > 0 ? offset : 0);
        List<Map<String, Object>> rows = getJdbc(dbName).queryForList(stmt);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 数据流接口 — 统一 {@link StreamingQuery} 和 {@link PartitionSequentialIterator} 的返回类型。
     *
     * <p>同时实现 {@link java.util.Iterator} 和 {@link AutoCloseable},
     * 调用方可用 try-with-resources 语法自动关闭底层连接。
     */
    public interface DataStream extends java.util.Iterator<List<Map<String, Object>>>, AutoCloseable {
    }

    public static class StreamingQuery implements DataStream {
        private final DataSource dataSource;
        private final Connection connection;
        private final PreparedStatement statement;
        private final ResultSet resultSet;
        private final int batchSize;
        private final boolean restoreAutoCommit;
        private final List<String> columnLabels;
        private final String tableName;
        private boolean hasNext;
        private boolean closed;

        StreamingQuery(DataSource dataSource, SqlStatement stmt, int batchSize) {
            this(dataSource, stmt, batchSize, null);
        }

        StreamingQuery(DataSource dataSource, SqlStatement stmt, int batchSize, String tableName) {
            Connection conn = null;
            PreparedStatement ps = null;
            ResultSet rs = null;
            boolean restore = false;
            try {
                conn = DataSourceUtils.getConnection(dataSource);
                restore = conn.getAutoCommit();
                if (restore) {
                    conn.setAutoCommit(false);
                }
                ps = conn.prepareStatement(stmt.getSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                ps.setFetchSize(batchSize);
                Object[] params = stmt.getParamArray();
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                rs = ps.executeQuery();

                this.dataSource = dataSource;
                this.connection = conn;
                this.statement = ps;
                this.resultSet = rs;
                this.batchSize = batchSize;
                this.tableName = tableName;
                this.restoreAutoCommit = restore;
                this.columnLabels = columnLabels(rs);
                this.hasNext = resultSet.next();
            } catch (SQLException e) {
                closeQuietly(rs, ps, conn, dataSource, restore);
                throw new RuntimeException("Failed to open streaming query", e);
            }
        }

        private static List<String> columnLabels(ResultSet rs) throws SQLException {
            ResultSetMetaData metaData = rs.getMetaData();
            List<String> labels = new ArrayList<>(metaData.getColumnCount());
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                labels.add(metaData.getColumnLabel(i));
            }
            return labels;
        }

        private static void closeQuietly(ResultSet rs, PreparedStatement ps, Connection conn,
                                         DataSource dataSource, boolean restoreAutoCommit) {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignored) {
                }
            }
            if (ps != null) {
                try {
                    ps.close();
                } catch (SQLException ignored) {
                }
            }
            if (conn != null) {
                if (restoreAutoCommit) {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException ignored) {
                    }
                }
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public List<Map<String, Object>> next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            try {
                List<Map<String, Object>> batch = new ArrayList<>();
                int count = 0;
                while (hasNext && count < batchSize) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < columnLabels.size(); i++) {
                        row.put(columnLabels.get(i), resultSet.getObject(i + 1));
                    }
                    batch.add(row);
                    count++;
                    hasNext = resultSet.next();
                }
                if (!hasNext) {
                    close();
                }
                return batch;
            } catch (SQLException e) {
                close();
                throw new RuntimeException("Failed to read streaming query", e);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                resultSet.close();
            } catch (SQLException e) {
                log.warn("Failed to close streaming ResultSet for table {}: {}", tableName, e.getMessage());
            }
            try {
                statement.close();
            } catch (SQLException e) {
                log.warn("Failed to close streaming PreparedStatement for table {}: {}", tableName, e.getMessage());
            }
            if (restoreAutoCommit) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException e) {
                    log.warn("Failed to restore streaming connection autoCommit for table {}: {}", tableName, e.getMessage());
                }
            }
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /**
     * 分区顺序流式读取迭代器 — 按分区顺序逐个读取,对调用方完全透明。
     *
     * <p>内部维护当前分区索引和当前分区的 {@link StreamingQuery},
     * 当前分区读完后自动切换到下一个分区。调用方通过 {@link Iterator}
     * 接口正常消费数据,无需感知分区逻辑。
     *
     * <p>使用 try-with-resources 自动关闭所有分区的连接。
     */
    public static class PartitionSequentialIterator implements DataStream {
        private static final String TABLE_PLACEHOLDER = "__TABLE__";

        private final DataSource dataSource;
        private final List<String> partitions;
        private final String baseSqlTemplate;
        private final List<Object> baseParams;
        private final int batchSize;
        private final String tableName;
        private int currentIndex;
        private StreamingQuery currentStream;
        private boolean closed;

        PartitionSequentialIterator(DataSource dataSource, List<String> partitions,
                                      String baseSqlTemplate, List<Object> baseParams, int batchSize,
                                      String tableName) {
            this.dataSource = dataSource;
            this.partitions = partitions;
            this.baseSqlTemplate = baseSqlTemplate;
            this.baseParams = baseParams;
            this.batchSize = batchSize;
            this.tableName = tableName;
            this.currentIndex = 0;
            this.currentStream = null;
            this.closed = false;
        }

        @Override
        public boolean hasNext() {
            if (closed) return false;
            if (currentStream != null && currentStream.hasNext()) {
                return true;
            }
            // 当前分区读完,切换到下一个
            closeCurrentStream();
            while (currentIndex < partitions.size()) {
                currentStream = openStreamForPartition(partitions.get(currentIndex++));
                if (currentStream.hasNext()) {
                    return true;
                }
                // 当前分区无数据,继续下一个
                closeCurrentStream();
            }
            return false;
        }

        @Override
        public List<Map<String, Object>> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return currentStream.next();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            closeCurrentStream();
        }

        private void closeCurrentStream() {
            if (currentStream != null) {
                try {
                    currentStream.close();
                } catch (Exception e) {
                    log.warn("Failed to close partition stream for table {}, partition {}: {}",
                            tableName, currentIndex > 0 ? partitions.get(currentIndex - 1) : "unknown", e.getMessage());
                }
                currentStream = null;
            }
        }

        private StreamingQuery openStreamForPartition(String partitionName) {
            String sql = baseSqlTemplate.replace(TABLE_PLACEHOLDER, partitionName);
            SqlStatement stmt = new SqlStatement(sql, baseParams);
            return new StreamingQuery(dataSource, stmt, batchSize, tableName);
        }
    }
}
