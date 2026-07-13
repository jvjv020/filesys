# audit 模块 — 审计服务

## 职责
提供传输过程的预审计（pre-audit）和后审计（post-audit），校验文件行数与数据库记录数是否一致。

## 关键类

| 类 | 角色 |
|---|------|
| `AuditService` | 审计服务入口，注入 `FtpPool` + `TargetTableRepository` |
| `AuditScenario` | 枚举：`UPLOAD` / `DOWNLOAD`，区分审计方向 |

## 核心方法

### `preAudit(scenario, source, auditCount, dbName)`
- UPLOAD：读取 FTP 文件行数 vs auditCount
- DOWNLOAD：COUNT 数据库记录数 vs auditCount
- 返回实际记录数（通过时），-1（不通过时）

### `preAuditByBucket(tableName, splitField, fieldValue, auditCount, dbName)`
- 分桶场景下按 `splitField + fieldValue` 做 COUNT 预审计

### `postAudit(scenario, ftpName, source, target, knownDbCount, dbName)`
- UPLOAD：文件行数 vs DB 记录数
- DOWNLOAD：DB 记录数 vs 文件行数（`knownDbCount >= 0` 时复用传入值，避免重复 COUNT）

## 设计约束
- `AuditScenario` 当前仅 DOWNLOAD 分支被引用（`DownloadSupport` / `SChildCommandProcessor`）
- UPLOAD 分支保留为协议占位，`UploadSupport` 直接实现自己的审计逻辑
- 不要通过 `new AuditScenario()` 创建实例
