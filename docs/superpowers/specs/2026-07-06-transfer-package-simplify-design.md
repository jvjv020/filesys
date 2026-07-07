# transfer 包精简设计

> 日期: 2026-07-06
> 目标: 在保证功能不变的前提下,将 transfer 包从 25 个类精简至 20 个类

## 现状

transfer 包目前 25 个类,约 3500 行,分布在 4 个子包中:

| 子包 | 文件数 | 主要职责 |
|------|--------|----------|
| `transfer/` | 8 | 入口 + 编排器 + 公共工具 |
| `transfer/upload/` | 7 | 上传 Handler + Support |
| `transfer/download/` | 8 | 下载 Handler + Support + Monitor |
| `transfer/placeholder/` | 1 | 占位符解析 |

## 精简策略

### 精简项 1: 删除 `UploadHandler` / `DownloadHandler` 标记接口

**当前**: 两个接口各 22 行,纯标记(extends TransferHandler),仅用于 Spring 按类型注入。

**改造**: 删除两个标记接口。Orchestrator 改为显式注入 3 个具体 Handler,用 if/else 按 scenario+commandType 路由,不再依赖 `supports()` 动态派发。

**效果**: -2 文件,-44 行。`TransferHandler` 接口可简化为仅保留 `handle()` 方法(可删除 `supports()`)。

### 精简项 2: 合并 `UploadOrchestrator` + `DownloadOrchestrator`

**当前**: 两个 Orchestrator 完全同构(Upload 54 行,Download 55 行),差别仅在于注入的 Handler 类型和方向字符串。

**改造**: 合并为单一 `TransferOrchestrator`,构造器同时注入上下载共 6 个 Handler + direction 字符串。`dispatch` 方法按 `direction` 分流到不同的 if/else 块。

**效果**: -1 文件(3 个 → 2 个,AbstractTransferOrchestrator 保留)。

### 精简项 3: `DirectoryUploadTask` → 内联为内部静态类

**当前**: 独立 Callable 类(113 行),只在 `MultiDirectoryUploadHandler` 中使用。构造器需要 8 个参数(ftpPool, ftpName, config, command, transferSupport, uploadSupport, fieldMappingBuilder)。

**改造**: 改为 `MultiDirectoryUploadHandler` 的内部静态类,可直接引用宿主字段,消除 8 参数构造器样板。

**效果**: -1 文件。功能零改动,可见性变窄(更佳封装)。

### 精简项 4: `TransferUtils` 内联 + 修 bug

**当前**: 独立工具类(96 行),仅 2 个静态方法。Javadoc 说"仅保留跨场景工具",但其方法实际是方向特定的。**该文件存在类定义重复的 bug**(两个 `TransferUtils` 类定义)。

**改造**:
- `splitFieldValues(names, values)` → 移到 `TransferSupport`,作为路径占位符上下文构建的辅助方法
- `rollbackAfterPostAuditFailure(client, path, reason)` → 移到 `DownloadSupport`,仅 Download 场景使用

**效果**: -1 文件。自动修复重复定义 bug。

### 精简项 5: `UploadSupport` + `DownloadSupport` 不合并,仅抽取公共状态判定

**分析**: 两 Support 方法名虽有重叠(preAudit/postAudit/determineMainStatus),但参数和语义完全不同(Upload 是 FTP 文件审计,Download 是 DB 审计)。强行合并会得到 400 行的"万能"类,降低内聚。

**改造**: 
- 保留两 Support 各自独立
- 将 `determineMainStatus` 逻辑抽取为 `TransferSupport` 的公共静态方法,两 Support 的 determineMainStatus 委托给公共方法
- `UploadResult`/`BucketSummary` 各自保留(方向不同,字段不同)

**效果**: 0 文件减少,约 -15 行样板代码。

### 保留不变的精简点

以下类职责清晰、边界明确,强拆反而降低可维护性,保留:
- `AbstractTransferOrchestrator` — 模板方法基类
- `TransferService` — 传输入口
- `TransferSupport` — 跨方向公共 Support
- `ParallelFileGenerator` — 分区并行文件生成(284 行,自包含)
- `ChildCommandMonitor` — 子命令后台监控(225 行,自包含)
- `TempTransferConfigFactory` — 临时配置工厂
- `FieldMappingBuilder` — 字段映射构建
- `BucketDistributor` — 分桶分发
- `PlaceholderResolver` — 占位符解析
- 5 个 Handler — 每个负责一个(scenario, commandType)组合

## 精简前后对比

### 类数量变化

| 子包 | 精简前 | 操作 | 精简后 |
|------|--------|------|--------|
| `transfer/` | 8 | TransferUtils 删除 | 7 |
| `transfer/upload/` | 7 | UploadHandler 删除 + DirectoryUploadTask 内联 | 5 |
| `transfer/download/` | 8 | DownloadHandler 删除, Orchestrator 移出 | 6 |
| `transfer/placeholder/` | 1 | 不变 | 1 |
| (新增根目录) | 0 | TransferOrchestrator(原 upload/download 各一) | 1 |
| **合计** | **25** | | **20** |

### 文件变动清单

| 操作 | 文件 | 说明 |
|------|------|------|
| **删除** | `transfer/upload/UploadHandler.java` | 标记接口,内联到 Orchestrator |
| **删除** | `transfer/download/DownloadHandler.java` | 同上 |
| **删除** | `transfer/upload/DirectoryUploadTask.java` | 变为 MultiDirectoryUploadHandler 内部类 |
| **删除** | `transfer/TransferUtils.java` | 两个方法分别移到 TransferSupport/DownloadSupport |
| **删除** | `transfer/upload/UploadOrchestrator.java` | 合并为 TransferOrchestrator |
| **删除** | `transfer/download/DownloadOrchestrator.java` | 合并为 TransferOrchestrator |
| **新增** | `transfer/TransferOrchestrator.java` | 合并后的单一编排器 |

### 接口变化

```java
// 精简前
public interface TransferHandler {
    boolean supports(TransferScenario scenario, CommandType commandType);
    void handle(Command command, TransferConfig config, Result result) throws Exception;
}
public interface UploadHandler extends TransferHandler {}   // 删除
public interface DownloadHandler extends TransferHandler {} // 删除

// 精简后
public interface TransferHandler {
    void handle(Command command, TransferConfig config, Result result) throws Exception;
    // supports() 删除 — 路由逻辑移到 TransferOrchestrator 的 if/else
}
```

### Handler 路由方式变化

```java
// 精简前 — 泛型 List + supports() 遍历
List<UploadHandler> handlers;
for (UploadHandler h : handlers) {
    if (h.supports(scenario, commandType)) { h.handle(...); return; }
}

// 精简后 — 显式 if/else
if (direction == UPLOAD) {
    if (scenario == UPLOAD_SINGLE) singleHandler.handle(...);
    else if (scenario == UPLOAD_MULTI && commandType == BATCH) multiBatchHandler.handle(...);
    else if (scenario == UPLOAD_MULTI) multiDirHandler.handle(...);
} else { /* DOWNLOAD */ ... }
```

## 风险与回滚策略

1. **功能不变**: 所有精简均为纯结构变化(合并、内联、移动),不改变业务逻辑。每个 Handler 的 `handle()` 方法体零改动。
2. **测试覆盖**: 无需新测试。精简后运行 `./gradlew.bat test --no-daemon` 验证回归。
3. **回滚**: 每次删除/合并分步提交,每个步骤可独立 revert。

## 实施顺序

1. 先做纯删除(标记接口、TransferUtils、DirectoryUploadTask 内联)
2. 再做合并(两个 Orchestrator 合一)
3. 最后修调用方(TransferService 改注入、Handler 去 supports())
4. 运行全量测试验证
