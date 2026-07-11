# enums 模块 — 枚举定义

## 职责
定义 FMSY 的核心枚举类型。

## 枚举说明

### `CommandType`
| 枚举 | DB code | 含义 |
|------|---------|------|
| `SERIAL` | `null` | 串行命令，同 `类别+控制` 必须在节点间串行 |
| `BATCH` | `"R"` | 批量命令，使用明细表的字段值或文件名 |
| `COORDINATED` | `"S"` | 协调命令，主命令创建的子命令 |
| `TEMPORARY` | `"T"` | 临时命令，内联 JSON 配置 |

**Always compare via the enum**, never use raw `"R"`/`"S"` literals.

### `TransferScenario`
| 枚举 | 含义 |
|------|------|
| `UPLOAD_SINGLE` | 单文件上传 |
| `UPLOAD_MULTI` | 多文件上传（目录匹配或明细表） |
| `DOWNLOAD_SINGLE` | 整表→单文件下载 |
| `DOWNLOAD_SINGLE_NODE` | 单节点分桶多文件下载 |
| `DOWNLOAD_MULTI_NODE` | 多节点协调分桶下载 |

方法 `isUpload()` 替代散弹式 `name().startsWith("UPLOAD")`。

### `EmptyDataHandling`
`ERROR` / `ALLOW` / `SEND_EMPTY` / `SKIP`

### `AuditScenario`
`UPLOAD` / `DOWNLOAD` — 由 audit 模块使用，区分审计方向。
