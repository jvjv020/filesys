# repository 模块 — 数据访问层

## 职责
集中管理对数据库表的所有 SQL 访问，**业务代码中不写 SQL**。

## 关键类

| 类 | 对应表 | 说明 |
|---|--------|------|
| `CommandRepository` | 指令表 | 竞争、超时释放、状态更新、子命令创建 |
| `DetailRepository` | 明细表 | 桶竞争、批量创建桶、状态更新 |
| `ResultRepository` | 结果表 | 完整结果写入 + 简化结果写入 |
| `TargetTableRepository` | 业务目标表 | 动态表名查询、流式查询、批量插入 |
| `TransferConfigRepository` | 传输配置表 | 加载所有有效配置 |

## 关键约定
- 所有 `SQL_*` 常量定义在各 Repository 类中
- 表名/列名通过 `TableNames` / `ColumnNames` 常量引用，**绝不硬编码中文名**
- SQL 中的 `?` 参数化绑定，表名列名走 `SqlBuilder` 白名单校验

### `TargetTableRepository` 特有约定
- `dbName + tableName` 双重入参（多数据源 + 动态表名）
- 提供 `DataStream` 接口统一 `StreamingQuery` 和 `PartitionSequentialIterator`
- `batchInsert` 手动拼多值 INSERT，一次网络往返
- 分区表自动优化：DISTINCT 查询按分区 → 流式查询按分区顺序

### `CommandRepository` 关键方法
- `compete(id, nodeId)` — 原子竞争，`UPDATE ... WHERE status='' AND node IS NULL`
- `markErrorWithResult(id, cat, ctrl, desc)` — 一站式"置 E + 写结果表"
- `batchCreateChildCommands(count, cat, ctrl, extraInfo, auditCount)` — 多值 INSERT
