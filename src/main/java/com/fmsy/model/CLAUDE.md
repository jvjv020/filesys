# model 模块 — 数据实体

## 职责
定义 FMSY 的核心数据实体，对应数据库表记录。

## 关键类

| 类 | 对应表 | 说明 |
|---|--------|------|
| `Command` | 指令表 | 任务队列实体 |
| `Detail` | 明细表 | 子任务/桶记录 |
| `Result` | 结果表 | 传输结果实体 |
| `TransferConfig` | 传输配置表 | 传输规则配置 |
| `FieldMapping` | — | 字段映射配置（非表实体） |

## 关键约定

### `Command`
- `markStartTimeIfAbsent()` — 幂等设置 startTime，替代 `if (startTime == null) setStartTime(...)` 模式
- `commandType` 用 `CommandType` 枚举，DB 存 code

### `Result`
- 14 个持久化字段 + 3 个瞬态字段（`dbName` / `startTimeMs` / `suppressStatusUpdate`）
- **使用 `Result.builder()` fluent API 构造**，替代 14 行 setter 链
- `markChildrenCreated()` — MultiNode 成功时调用（抑制指令表终态落库）
- `markChildrenFailed(reason)` — MultiNode 失败时调用
- `suppressStatusUpdate = true` 时 Orchestrator 跳过更新指令表

### `TransferConfig`
- 不包含 `SQL_*` 常量，所有 SQL 在 Repository 层
- `scenario` / `commandType` / `emptyDataHandling` 使用枚举

### `FieldMapping`
- `tableFields`：数据库表字段列表
- `extraFields`：额外固定值映射
- `getValue(record, fieldName)`：record 优先 → extraFields 回退
