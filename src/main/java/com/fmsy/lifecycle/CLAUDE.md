# lifecycle 模块 — 应用生命周期

## 职责
管理应用的启动初始化、配置加载、优雅关闭和健康检查。

## 关键类

| 类 | 角色 | 触发时机 |
|---|------|---------|
| `StartupService` | 启动初始化 + 异常恢复 | `@EventListener(ApplicationReadyEvent.class)` |
| `StartupHealthCheck` | 启动健康检查 | `@EventListener(ApplicationReadyEvent.class)`，`@Order(0)` 先于 StartupService |
| `ConfigLoaderService` | 从 DB 加载传输配置到内存 | 由 StartupService.onApplicationReady 触发 |
| `ShutdownService` | 优雅关闭管理 | `@EventListener(ContextClosedEvent.class)` |

## 启动顺序
1. `StartupHealthCheck.healthCheck()` — 验证 DB/FTP 连通性，DB 不可达时 `System.exit(1)`
2. `StartupService.onApplicationReady()` — 初始化节点 ID → DB 探测 → 加载配置 → 恢复异常任务

## 配置加载
- `ConfigLoaderService` 启动时从 `传输配置表` 加载所有 VALID 配置到 `configMap`
- 运行时只查内存，不重新查 DB
- `getConfigOrThrow(cat, ctrl)` — 配置缺失时抛 `TransferException`
- `getConfigOrDefault(cat, ctrl)` — 配置缺失时返回 null（WARN），供容错路径使用

## 优雅关闭
1. `ShutdownService.initiateShutdown()` — 标记关闭状态
2. `ShutdownService.awaitTasksComplete(timeout)` — 等待在飞任务完成
3. `FtpPool.close()` / `DbPool.close()` — 在 `@PreDestroy` 中回收资源
