# util 模块 — 通用工具

## 职责
提供项目中跨模块共享的常量、工具方法和值对象。

## 关键类

| 类 | 用途 |
|---|------|
| `ColumnNames` | **所有数据库列名的中文常量** + 5 个状态码（`STATUS_EMPTY` / `STATUS_PROCESSING` / `STATUS_SUCCESS` / `STATUS_SKIPPED` / `STATUS_ERROR`） + `DEFAULT_DB` |
| `TableNames` | 4 个数据库表名的中文常量 |
| `BooleanUtils.isYes(String)` | null-safe 大小写不敏感 `"Y"` 判断 |
| `DateUtils` | `yyyyMMdd` / `yyyyMMddHHmmss` 格式化 |
| `FilePathUtils` | 路径提取 + 路径遍历攻击校验 |
| `LogUtils` | MDC 日志链路追踪（taskId / nodeId） |
| `ParserConfigUtil` | 手写 JSON 解析器（不依赖外部 JSON 库） |
| `ResolvedPath` | 文件路径衍生信息值对象（stem/name/ext/dir/dn/up） |
| `SystemConstants` | `DEFAULT_BATCH_SIZE(1000)` / `MAX_RETRIES(1)` / `MONITOR_*` 常量 |

## 关键约定
- **不要硬编码 `"Y"`/`"N"`/`"E"`**，使用 `ColumnNames.STATUS_*` 常量
- **不要硬编码中文表名/列名**，使用 `TableNames` / `ColumnNames`
- `BooleanUtils.isYes()` 替代散弹式 `"Y".equalsIgnoreCase(...)`
- `ColumnNames.DEFAULT_DB = "DB_DEFAULT"` — 默认数据源 ID
