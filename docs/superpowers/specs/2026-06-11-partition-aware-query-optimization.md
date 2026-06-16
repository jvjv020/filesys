# 分区表查询优化设计

> **版本:** v1.0
> **日期:** 2026-06-11
> **状态:** 已批准

## 1. 背景

当前 FMSY 在下发数据时，`BucketDistributor.distinctBuckets()` 使用 `SELECT DISTINCT splitFields FROM tableName ORDER BY splitFields` 获取分桶值，各个 Handler 使用 `streamBucketData` / `streamQueryBatches` 读取目标表数据。

对于**分区表**，这些查询会扫描所有分区。如果表是按分区键（如日期、区域）分区的，按分区顺序逐个查询可以利用分区剪枝，减少扫描范围。

**关键原则：** 服务层（Handler / Orchestrator）不应感知分区逻辑。优化完全封装在数据访问层内部。

## 2. 设计方案（方案 C）

### 2.1 架构

```
┌─────────────────────────────────────────────────────────────┐
│                   TargetTableRepository                      │
│                                                             │
│  querySmallResult(dbName, tableName, fields, distinct,      │
│                    predicates, params, orderBy, limit)       │
│       │                                                     │
│       ├─ [新] 如果是 DISTINCT 分区表 →                       │
│       │    委托 PartitionHelper.scanPartitionsDistinct()    │
│       │    按分区顺序逐个查询并合并 DISTINCT 结果             │
│       │                                                     │
│       └─ [旧] 非分区表 → 原逻辑不变                           │
│                                                             │
│  streamBucketData(dbName, tableName, splitField, val)       │
│  streamQueryBatches(dbName, tableName, ...)                  │
│       │                                                     │
│       ├─ [新] 如果是分区表 →                                  │
│       │    委托 PartitionHelper.scanPartitionSequential()    │
│       │    按分区顺序流式读取数据（合并为一个流）              │
│       │                                                     │
│       └─ [旧] 非分区表 → 原逻辑不变                           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
                           ▲
                           │
    ┌──────────────────────┴──────────────────────┐
    │           PartitionHelper (@Service)          │
    │                                              │
    │  职责: 检测分区表 + 按分区顺序查询             │
    │                                              │
    │  + isPartitioned(dbName, tableName): boolean  │
    │  + getPartitions(dbName, tableName): List     │
    │  + scanPartitionsDistinct(...): List<Map>     │
    │  + scanPartitionSequential(...): Streaming    │
    │                                              │
    │  缓存: ConcurrentHashMap<db+table, PartitionInfo> │
    │  缓存 TTL: 5 分钟（启动时加载,运行时刷新）      │
    └──────────────────────────────────────────────┘
```

### 2.2 并发策略

**不引入分区级并发读。** 理由：

| 场景 | 数据量 | 为何不并发 |
|------|--------|-----------|
| DISTINCT 分桶值 | 极小 | 查询微秒级，线程开销 > 查询本身 |
| 桶数据流式读 | 中~大 | handler 层已有桶级并发（`SingleNodeDownloadHandler` 线程池），再加分区级并发导致两层嵌套，耗尽连接池 |
| 单表全量流式读 | 大 | 保持流式 Iterator 契约，多线程合并破坏流式 |

**在 `PartitionHelper` 中保留扩展点：** 分区迭代逻辑使用清晰的结构化循环，未来如需并发只需将循环体改为并行执行即可。

### 2.3 分区表检测

使用 PostgreSQL/GaussDB 兼容的 `information_schema` 查询分区信息：

```sql
-- 查询表是否为分区表
SELECT relkind = 'p' AS is_partitioned
FROM pg_class
WHERE relname = ?;

-- 查询分区子表列表（按分区名排序）
SELECT inhrelid::regclass::text AS partition_name
FROM pg_inherits
WHERE inhparent = ?::regclass
ORDER BY partition_name;
```

检测结果缓存在 `ConcurrentHashMap` 中，避免每次查询都查元数据。

**缓存键:** `dbName + "|" + tableName`
**缓存值:**
```java
class PartitionInfo {
    boolean partitioned;       // 是否为分区表
    List<String> partitions;   // 分区子表列表（已排序）
    long cachedAt;             // 缓存时间戳
}
```

### 2.4 核心逻辑

#### 2.4.1 DISTINCT 查询优化

```
原: SELECT DISTINCT field1, field2 FROM tableName ORDER BY field1, field2

优化为（分区表时）:
  -- 按分区顺序逐个查询
  SELECT DISTINCT field1, field2 FROM partition_1 ORDER BY field1, field2;
  SELECT DISTINCT field1, field2 FROM partition_2 ORDER BY field1, field2;
  ...合并结果（去重）...
```

每个分区查询结果集极小（分桶值通常几十到几百个），合并去重开销可忽略。

#### 2.4.2 流式查询优化

```
原: SELECT * FROM tableName WHERE bucketField = ? ORDER BY field1

优化为（分区表时）:
  -- 每个分区单独流式读取，按分区顺序拼接
  SELECT * FROM partition_1 WHERE bucketField = ? ORDER BY field1;
  SELECT * FROM partition_2 WHERE bucketField = ? ORDER BY field1;
  ...顺序拼接为一个流...
```

**实现方式：** 包装为 `Iterator<List<Map<String, Object>>>`，内部维护当前分区索引和当前分区的流，当前分区读完后自动切换到下一个分区。对调用方完全透明。

#### 2.4.3 非分区表

不做任何改变，直接走原逻辑。

### 2.5 边界条件

| 场景 | 行为 |
|------|------|
| 表不是分区表 | 原逻辑不变，零开销 |
| 表不存在 | `getPartitions()` 返回空列表 → 回退到原逻辑 |
| 分区信息查询失败 | 捕获异常，回退到原逻辑，日志警告 |
| 分区列表为空（分区表但无子表） | 回退到原逻辑 |
| 缓存过期 | 重新查询元数据，缓存 5 分钟 |
| 多线程并发访问缓存 | `ConcurrentHashMap.computeIfAbsent` 保证线程安全 |

### 2.6 缓存设计

```java
class PartitionHelper {
    private final ConcurrentHashMap<String, PartitionInfo> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 分钟

    PartitionInfo getPartitionInfo(String dbName, String tableName) {
        String key = dbName + "|" + tableName;
        PartitionInfo info = cache.get(key);
        if (info != null && (System.currentTimeMillis() - info.cachedAt) < CACHE_TTL_MS) {
            return info;
        }
        // 重新查询并更新缓存
        info = loadPartitionInfo(dbName, tableName);
        cache.put(key, info);
        return info;
    }
}
```

### 2.7 涉及的变更文件

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `db/PartitionHelper.java` | **重写** | 替换当前的空文件，实现分区检测+顺序查询 |
| `repository/TargetTableRepository.java` | **修改** | `querySmallResult` 中判断分区表并委托 |
| `transfer/BucketDistributor.java` | **不修改** | 服务层无感知，仍调用 `querySmallResult` |

注意：`streamQueryBatches` 和 `streamBucketData` 返回的是 `StreamingQuery`（一个 Iterator），无法透明替换为分区顺序流，因为 `StreamingQuery` 内部持有 `DataSource` 且构造时直接执行 SQL。分区顺序流需要**新的 StreamingQuery 包装类**或**在 PartitionHelper 中实现分区迭代逻辑**。

### 2.8 分区顺序流式读取实现（`PartitionSequentialIterator`）

由于 `StreamingQuery` 构造时立即执行 SQL，分区顺序读取需要一个新的包装器：

```java
class PartitionSequentialIterator implements Iterator<List<Map<String, Object>>>, AutoCloseable {
    private final DataSource dataSource;
    private final List<String> partitions;       // 分区子表列表
    private final SqlStatement baseStmt;          // 基础 SQL（不含表名）
    private final int batchSize;
    private int currentPartitionIndex;
    private StreamingQuery currentStream;        // 当前分区的流
    
    @Override
    public boolean hasNext() {
        while (currentStream == null || !currentStream.hasNext()) {
            closeCurrentStream();
            if (currentPartitionIndex >= partitions.size()) {
                return false;
            }
            // 切换到下一个分区
            currentStream = createStreamForPartition(partitions.get(currentPartitionIndex++));
        }
        return true;
    }
    
    @Override
    public List<Map<String, Object>> next() {
        return currentStream.next();
    }
}
```

对 `TargetTableRepository` 的 `streamBucketData` 和 `streamQueryBatches` 增加分区感知重载，返回 `PartitionSequentialIterator` 而非 `StreamingQuery`。两者都实现 `Iterator<List<Map<String,Object>>>`，调用方无需改动。

实际上，`StreamingQuery` 已经是 `Iterator<List<Map<String, Object>>>`，而 `PartitionSequentialIterator` 也实现同样的接口——因此 handler 代码无需改动。

### 2.9 测试策略

由于项目无测试环境，不做自动化测试。变更后的验证通过编译检查保证。
