# ftp 模块 — FTP 连接池与客户端

## 职责
管理 FTP 连接池，提供文件上传/下载/删除/重命名/行数统计/MD5 等操作。

## 关键类

| 类 | 角色 |
|---|------|
| `FtpPool` | FTP 连接池管理器，`@Component`，多服务器独立池 |
| `FtpClient` | FTP 客户端封装，通过 `FtpPool.getClient()` 获取 |

## 连接池设计
- 每 FTP 服务器独立 `FtpConfigHolder`，持有独立的 `ReentrantLock`
- `idle`：空闲连接（带时间戳），`busy`：正在使用（仅计数）
- 借连接：优先复用 idle 有效连接 → 池满等待 → 创建新连接
- 归还：NOOP 验证 → 有效回 idle / 无效销毁
- 健康检查：可配置 NOOP daemon 定期清理超时空闲连接

## 故障转移
- 通过 DNS `InetAddress.getAllByName` 解析多 IP
- 启用 failover 时遍历所有 IP 地址尝试连接

## 最佳实践
- 优先使用 `withClient(ftpId, callback)` 即借即用模板
- 仅当需要早返/分阶段控制流时使用 `getClient()` + 手动 `close()`
- 发生 IOException 时自动重试一次（换新连接）
