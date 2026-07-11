# config 模块 — 应用配置

## 职责
管理 FMSY 的全部配置项：应用参数、多数据源、FTP 连接池、线程池工厂、外部配置加载。

## 关键类

| 类 | 角色 |
|---|------|
| `AppConfig` | 应用配置，`@ConfigurationProperties(prefix = "app")`，含 node/polling/download 子配置 |
| `DataSourceConfig` | 多数据源配置，构建 `DbPool`（Druid 连接池管理器） |
| `FtpPoolConfig` | FTP 配置，`@ConfigurationProperties(prefix = "ftp.config")` |
| `AsyncConfig` | 线程池工厂 Bean，供 PollingService 和 Handler 创建隔离的 `ExecutorService` |
| `ExternalConfigLoader` | 从 `config/{format}/{cat}_{ctrl}` 文件加载外部配置（XSL/XSD/fields.json） |

## 关键约定

### `DataSourceConfig.DbPool`
- `getJdbcTemplate(id)` — 懒创建并缓存 `JdbcTemplateWrapper`
- `resolveJdbcTemplate(dbName)` — null/空回退到 `ColumnNames.DEFAULT_DB`，**优先使用此方法**
- `getTransactionTemplate(id)` — 每次新建（无状态，不缓存）
- `@PreDestroy close()` — 关闭所有 Druid 连接池

### `AppConfig.Download`
- `bucketBatchSize` 默认 3
- `maxPollIterations` 默认 1000（S 子命令外层轮询安全上限）
- `parallelThreads` 默认 3（分区并行文件生成）

### 密码管理
- `${DB_PASSWORD}` / `${FTP_PASSWORD}` 环境变量注入
- 不要在代码或配置中硬编码密码
