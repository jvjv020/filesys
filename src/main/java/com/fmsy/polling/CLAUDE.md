# polling 模块 — 轮询调度

## 职责
定时从数据库查询待处理命令，竞争执行权，派发到线程池处理。

## 关键类

| 类 | 角色 |
|---|------|
| `PollingService` | 轮询入口，`@Scheduled(fixedDelay)` 驱动每轮轮询 |
| `BatchDispatcher` | 单轮派发器（约束检查 → 竞争 → 配置查询 → submit） |
| `DetailPollingService` | S 型子命令（桶）轮询处理 |
| `SerialConstraintChecker` | 串行约束检查 |
| `CommandProcessingTracker` | 处理中命令跟踪器（内存串行约束） |

## 每轮轮询流程
1. `PollingService.poll()` — 释放超时任务 → 加载处理中命令 → 查询 ready 命令
2. `BatchDispatcher.dispatch()` — 串行约束检查 → 原子竞争 → 查询配置 → submit 到线程池
3. 异步执行 `TransferService.process(commandId, direction)`

## 线程池模型
- **每轮独立** `ExecutorService`（lazy 创建，dispatch 完毕 shutdown）
- 隔离性：上一轮未完成的任务不阻塞下一轮
- `ShutdownService.beginTask()/endTask()` 包裹每个提交，确保优雅关闭可等待

## 桶竞争流程（DOWNLOAD_MULTI_NODE）
1. `DetailPollingService.pollAndProcess()` — 外层循环按批次拉取待处理桶
2. 每批次独立线程池并行处理桶
3. 每个桶：原子竞争 → 预审计 → 空数据处理 → 查询数据 → 生成文件 → 后处理 → 后审计
