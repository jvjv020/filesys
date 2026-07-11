# db 模块 — 数据库工具层

## 职责
提供 JDBC 封装、SQL 构建、表元数据读取、分区表查询优化等数据库基础设施。

## 关键类

| 类 | 角色 |
|---|------|
| `JdbcTemplateWrapper` | `JdbcTemplate` 轻量封装，提供 `{String, Object...}` 与 `{SqlStatement}` 两种重载 |
| `SqlBuilder` | SQL 构建工具，表名列名走白名单正则校验，WHERE 条件走 `?` 参数化绑定 |
| `SqlStatement` | SQL + 参数列表封装，防止 SQL 注入 |
| `TableMetadataService` | 从 `information_schema.columns` 读取表字段顺序 |
| `PartitionHelper` | 分区表检测与分区顺序查询优化（5 分钟 TTL 缓存） |

## 安全约束
- `SqlBuilder.isValidIdentifier` — 只允许 `^[a-zA-Z_][a-zA-Z0-9_]*$`
- 所有用户可控输入走 `?` 参数化绑定
- 拒绝非标识符表名（防止 SQL 注入）

## 分区表优化
- `PartitionHelper` 通过 `pg_catalog.pg_inherits` 检测分区子表
- 分区 DISTINCT 查询：各分区单独 DISTINCT → 合并去重
- 分区流式查询：`PartitionSequentialIterator` 按分区顺序逐个读取
- 非分区表自动降级为普通流式查询

## 注意
- 不要直接持有 `JdbcTemplate`，应通过 `JdbcTemplateWrapper` 访问
- Repository 层是唯一的 SQL 编写位置，业务代码不直接使用本模块类
