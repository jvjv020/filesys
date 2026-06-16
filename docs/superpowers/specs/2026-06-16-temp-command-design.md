# 临时指令设计文档

## 1. 概述

### 1.1 背景

现有 FMSY 系统的指令处理链路为：指令表 `(categoryCode + controlCode)` → 查询`传输配置表` → 获取 `TransferConfig` → 派发到 Handler 执行。这意味着每条指令必须在传输配置表中预配置一条对应的规则，无法灵活处理"一次性的、参数自包含"的传输任务。

### 1.2 目标

支持一种新的"临时指令"类型，所有传输参数（数据库、接口表、文件路径、FTP、解析器、前置/后置操作等）直接以 JSON 格式携带在指令记录中，**不依赖传输配置表**，其余处理链路（Orchestrator → Handler → Converter → FileOps）完全复用现有逻辑。

### 1.3 设计原则

- **最小侵入**：不修改现有 Handler / Converter / FileOps 等核心执行链路
- **全量兼容**：支持所有 5 种传输场景（UPLOAD_SINGLE / UPLOAD_MULTI / DOWNLOAD_SINGLE / DOWNLOAD_SINGLE_NODE / DOWNLOAD_MULTI_NODE）
- **extraInfo 协议不动**：extraInfo 的 `"mainId|baseFilePath"` 协议是 S 子命令的核心契约，不做修改
- **新增一列**：指令表新增 `temp_config TEXT` 列，T 类型指令的 JSON 参数存于此列

---

## 2. 方案选择：构建临时 TransferConfig

在 `BatchDispatcher` 和 `TransferService` 中检测到 T 类型指令时，从 `temp_config` 的 JSON 解析出 `TransferConfig` 对象，后续整条链路（Orchestrator → Handler → Converter → FileOps）无需感知"这是临时指令"。

```
指令表 (指令类型='T', temp_config='{json}', 额外信息=空)
    │
    ├── BatchDispatcher.dispatch()
    │   └── 检测 CommandType.TEMPORARY → TempConfigFactory.build(command)
    │       → 得到 TransferConfig → 后续同普通指令
    │
    └── TransferService.process()
        └── 检测 CommandType.TEMPORARY → TempConfigFactory.build(command)
            → 得到 TransferConfig → 后续同普通指令
```

对于 DOWNLOAD_MULTI_NODE 场景的 S 型子命令：

```
S 子命令 (指令类型='S', 额外信息='mainId|baseFilePath')
    │
    └── DetailPollingService.pollAndProcess()
        └── configLoader 查不到配置
            → 从 extraInfo 解析 mainCommandId → 查主命令
            → 主命令是 T 类型 → TempConfigFactory.build(mainCommand)
            → 得到 TransferConfig → 后续同普通 S 子命令
```

---

## 3. 变更清单

### 3.1 新增枚举值：`CommandType.TEMPORARY("T")`

```java
public enum CommandType {
    SERIAL(null),
    BATCH("R"),
    COORDINATED("S"),
    TEMPORARY("T");   // 新增
}
```

- 序列化到指令表 `指令类型` 列的值为 `"T"`
- `fromCode("T")` 返回 `TEMPORARY`
- 不影响已有的 `SERIAL(null)` / `BATCH("R")` / `COORDINATED("S")` 逻辑

### 3.2 指令表新增列：`temp_config`

新增 SQL：

```sql
ALTER TABLE 指令表 ADD COLUMN temp_config TEXT;
```

**设计要点**：
- 仅在 `指令类型='T'` 时有值，其他类型此列为空
- `extraInfo`（额外信息）回归原有用途：S 子命令存 `"mainId|baseFilePath"`，T 类型主命令为空

### 3.3 新增常量：`ColumnNames.TEMP_CONFIG`

```java
public static final String TEMP_CONFIG = "temp_config";
```

### 3.4 修改 `Command` 实体

新增字段：

```java
/** 临时指令 JSON 配置（仅指令类型='T' 时有值） */
private String tempConfig;
```

### 3.5 修改 `CommandRepository`

**`findReadyCommands`**：在 SELECT 列表中增加 `temp_config` 列

```java
// 原 SELECT 字段列表后追加 , ColumnNames.TEMP_CONFIG
// RowMapper 中增加：
cmd.setTempConfig(rs.getString(ColumnNames.TEMP_CONFIG));
```

**`findById`**：同样增加 `temp_config` 列的查询和映射

```java
// 原 SELECT 字段列表后追加 , ColumnNames.TEMP_CONFIG
// 映射代码中增加：
cmd.setTempConfig((String) row.get(ColumnNames.TEMP_CONFIG));
```

### 3.6 新增类：`TempTransferConfigFactory`

包路径：`com.fmsy.transfer.TempTransferConfigFactory`

职责：将 `command.getTempConfig()` 中的 JSON 字符串解析为 `TransferConfig` 实体。

```java
@Component
public class TempTransferConfigFactory {

    /**
     * 从 command.tempConfig JSON 构建 TransferConfig。
     * @param command 指令（必须为 TEMPORARY 类型）
     * @return 完整的 TransferConfig
     * @throws TransferException JSON 解析失败或缺少必要字段时抛出
     */
    public TransferConfig build(Command command) { ... }
}
```

**JSON 格式约定**：

```json
{
  "scenario": "UPLOAD_SINGLE",
  "dbName": "DB1",
  "tableName": "student",
  "ftpName": "FTP1",
  "filePath": "/data/upload/student_$YYYYMMDD$.csv",
  "parserType": "CSV",
  "clearTableFlag": "N",
  "overwriteFlag": "Y",
  "concurrency": 3,
  "serialFlag": "N",
  "nodeId": "",
  "parserConfig": "",
  "preOperations": "READY:/data/flag/ready.flg",
  "postOperations": "FB:/data/flag/result.flg;C",
  "ignoreFields": "col1,col2",
  "splitFields": "REGION",
  "emptyDataHandling": "ERROR"
}
```

**字段映射说明**：

| JSON 键 | TransferConfig 字段 | 类型 | 必填 | 说明 |
|---------|--------------------|------|------|------|
| scenario | setScenario | String→enum | ✅ | 对应 TransferScenario 枚举名 |
| dbName | setDbName | String | ✅ | 数据库连接 ID |
| tableName | setTableName | String | ✅ | 目标表名 |
| ftpName | setFtpName | String | ✅ | FTP 连接名 |
| filePath | setFilePath | String | ✅ | 含占位符的文件路径 |
| parserType | setParserType | String | ✅ | DBF/XML/CSV/TXT |
| clearTableFlag | setClearTableFlag | String | ❌ | 默认 "N" |
| overwriteFlag | setOverwriteFlag | String | ❌ | 默认 "Y" |
| concurrency | setConcurrency | Integer | ❌ | 默认 null |
| serialFlag | setSerialFlag | String | ❌ | 默认 "N" |
| nodeId | setNodeId | String | ❌ | 默认 null |
| parserConfig | setParserConfig | String | ❌ | 默认 null |
| preOperations | setPreOperations | String | ❌ | 默认 null |
| postOperations | setPostOperations | String | ❌ | 默认 null |
| ignoreFields | setIgnoreFields | String | ❌ | 默认 null |
| splitFields | setSplitFields | String | ❌ | 默认 null |
| emptyDataHandling | setEmptyDataHandling | String→enum | ❌ | 默认 ALLOW |

**解析实现**：
- 使用已有的 `ParserConfigUtil.parseJson()` 将 JSON 解析为 `Map<String,String>`
- 逐字段提取、类型转换、设置到 `TransferConfig`
- `scenario` 用 `TransferScenario.valueOf()` 转换
- `emptyDataHandling` 用 `EmptyDataHandling.valueOf()` 转换
- 缺失必填字段时抛 `TransferException("TEMP_MISSING_FIELD", "Missing required field: xxx")`

### 3.7 修改 `SerialConstraintChecker`

T 类型指令不参与串行约束，直接放行：

```java
public boolean check(Command command, Map<String, CommandProcessingTracker> processingMap) {
    // T 类型临时指令不参与串行约束
    if (command.getCommandType() == CommandType.TEMPORARY) {
        return true;
    }
    // ... 原有逻辑
}
```

### 3.8 修改 `BatchDispatcher.dispatch()`

在步骤 3（查询配置）处增加 T 类型分支，注入 `TempConfigFactory`：

```java
// 原代码：
TransferConfig config = configLoader.getConfigOrDefault(cmd.getCategoryCode(), cmd.getControlCode());

// 改为：
TransferConfig config;
if (cmd.getCommandType() == CommandType.TEMPORARY) {
    config = tempConfigFactory.build(cmd);
} else {
    config = configLoader.getConfigOrDefault(cmd.getCategoryCode(), cmd.getControlCode());
}
```

当 `config == null`（T 类型 JSON 解析失败）时，同样走 `markErrorWithResult` 逻辑。

### 3.9 修改 `TransferService.process()`

同样在配置查询处增加 T 类型分支，注入 `TempConfigFactory`：

```java
// 原代码：
TransferConfig config = configLoader.getConfigOrDefault(
    command.getCategoryCode(), command.getControlCode());

// 改为：
TransferConfig config;
if (command.getCommandType() == CommandType.TEMPORARY) {
    config = tempConfigFactory.build(command);
} else {
    config = configLoader.getConfigOrDefault(
        command.getCategoryCode(), command.getControlCode());
}
```

### 3.10 修改 `DetailPollingService` — 支持 T 类型主命令的 S 子命令

**注入**：增加 `CommandRepository` 和 `TempConfigFactory`

**修改 `writeSubCommandResult`**：config 查询失败时回溯主命令

```java
// 原代码：
TransferConfig config = configLoader.getConfigOrDefault(
    subCommand.getCategoryCode(), subCommand.getControlCode());

// 改为：
TransferConfig config = resolveTempConfig(subCommand);
// ...
Result result = Result.builder()
    .commandId(subCommand.getId())
    .categoryCode(subCommand.getCategoryCode())
    .controlCode(subCommand.getControlCode())
    .ftpName(config != null ? config.getFtpName() : null)
    .filePath(config != null ? config.getFilePath() : null)
    .dbInfo(config != null ? config.getTableName() : null)
    // ... 其余不变
```

**修改 `processBucket`**：config 查询失败时回溯主命令

```java
// 原代码：
TransferConfig config = configLoader.getConfigOrDefault(
    bucket.getCategoryCode(), bucket.getControlCode());

// 改为：
TransferConfig config = resolveTempConfigForBucket(bucket);
```

**新增辅助方法**：

```java
/**
 * 解析 S 子命令对应的 TransferConfig。
 * 优先查传输配置表，若查不到且主命令是 T 类型，则从主命令的 temp_config 构建。
 */
private TransferConfig resolveTempConfig(Command subCommand) {
    String cat = subCommand.getCategoryCode();
    String ctrl = subCommand.getControlCode();
    TransferConfig config = configLoader.getConfigOrDefault(cat, ctrl);
    if (config != null) {
        return config;
    }
    // 从 extraInfo 解析主命令 ID (格式: "mainId|baseFilePath")
    String extraInfo = subCommand.getExtraInfo();
    if (extraInfo == null || !extraInfo.contains("|")) {
        return null;
    }
    String mainIdStr = extraInfo.substring(0, extraInfo.indexOf('|'));
    try {
        Command mainCommand = commandRepository.findById(Long.parseLong(mainIdStr));
        if (mainCommand != null && mainCommand.getCommandType() == CommandType.TEMPORARY) {
            return tempConfigFactory.build(mainCommand);
        }
    } catch (Exception e) {
        log.warn("Failed to resolve temp config from main command: {}", e.getMessage());
    }
    return null;
}
```

> **注**：`resolveTempConfigForBucket` 接收 `Detail` 而非 `Command`，需要先通过 `detailRepository` 查询子命令 ID 或通过其他方式获取主命令 ID。实际实现时可以简化为：在 `pollAndProcess` 入口处一次性解析 config 并传递到各方法。

### 3.11 修改 `ChildCommandMonitor` — 支持 T 类型 TOTAL_FLAG

**注入**：增加 `CommandRepository` 和 `TempConfigFactory`

**修改 `updateMainCommandStatus`**：TOTAL_FLAG 阶段获取 config 时增加回溯：

```java
// 原代码：
TransferConfig config = configLoader.getConfigOrDefault(categoryCode, controlCode);

// 改为：
TransferConfig config = configLoader.getConfigOrDefault(categoryCode, controlCode);
if (config == null && mainCommandId != null) {
    Command mainCmd = commandRepository.findById(mainCommandId);
    if (mainCmd != null && mainCmd.getCommandType() == CommandType.TEMPORARY) {
        config = tempConfigFactory.build(mainCmd);
    }
}
```

### 3.12 `PollingService.loadProcessingCommands()` — 无变更

T 类型命令的 P 状态也会被加载到 `processingMap`，但 `SerialConstraintChecker` 已跳过 T 类型，因此不影响串行约束。

### 3.13 `Result.markEnd()` — 无变更

`markEnd(command, config)` 读取 `config.getFtpName()`, `config.getFilePath()`, `config.getTableName()` — 从 JSON 构建的 TransferConfig 中这些字段都已填充，无需修改。

### 3.14 `AbstractTransferOrchestrator.finalize()` — 无变更

使用 `config.getDbName()` 选择事务模板，JSON 中已包含 `dbName`。

### 3.15 `BucketDistributor.createChildCommands()` — 无变更

S 子命令的 `extraInfo` 格式保持 `"mainId|baseFilePath"` 不变，不涉及 JSON 透传。

---

## 4. 处理流程

### 4.1 指令插入

业务方直接向`指令表` INSERT 一条记录：

| 列 | 值 | 说明 |
|---|-----|------|
| 类别代号 | `TEMP_xxx` | 约定以 `TEMP_` 前缀标识临时类别 |
| 控制代号 | 自定 | 区分同一批次的不同临时指令 |
| 指令类型 | `T` | CommandType.TEMPORARY |
| temp_config | `{json}` | 完整传输参数 JSON |
| 额外信息 | 空 | 不使用 |
| 稽核数 | 可选 | 预审计行数 |
| 处理状态 | `空` | 就绪状态 |

### 4.2 普通上下传流程（非 MULTI_NODE）

```
PollingService.poll()
    → releaseTimeoutTasks()
    → loadProcessingCommands()
    → findReadyCommands()
    → BatchDispatcher.dispatch()
        → SerialConstraintChecker.check() → true (T 类型跳过)
        → compete() → 正常竞争
        → config = TempConfigFactory.build(cmd)  // 从 temp_config JSON 构建
        → submit → runTransfer(commandId, direction)

runTransfer() → TransferService.process()
    → commandRepository.findById()
    → config = TempConfigFactory.build(cmd)     // 再次从 JSON 构建
    → 根据 scenario.isUpload() 选择方向
    → UploadOrchestrator/DownloadOrchestrator.execute(command, config)
        → dispatch → 匹配现有 Handler
        → Handler.handle() 正常执行
        → finalize → Result.markEnd() → updateStatus + insertResult
```

### 4.3 DOWNLOAD_MULTI_NODE 流程

```
T 类型主命令 (指令类型='T', temp_config='{json}')
    │
    ├── BatchDispatcher.dispatch() → 竞争成功
    │   └── TempConfigFactory.build() → config → submit
    │
    ├── TransferService.process()
    │   └── TempConfigFactory.build() → config
    │   └── DownloadOrchestrator.execute(command, config)
    │       └── MultiNodeDownloadHandler.handle()
    │           ├── 创建明细桶 (detailRepository.createBuckets)
    │           ├── 创建 S 子命令 (BucketDistributor.createChildCommands)
    │           │   └── extraInfo = "mainCommandId|baseFilePath" (不变)
    │           └── ChildCommandMonitor.start(mainCommandId, expectedChildren)
    │
    └── S 子命令被各节点竞争
        └── TransferService.process()
            └── 检测到 COORDINATED → DetailPollingService.pollAndProcess()
                └── configLoader 查不到配置 (因为 T 类型没有传输配置表记录)
                    └── resolveTempConfig():
                        1. 从 S 子命令 extraInfo 解析 mainCommandId
                        2. commandRepository.findById(mainCommandId)
                        3. 主命令是 T 类型 → TempConfigFactory.build(mainCommand)
                        4. 得到 TransferConfig → 正常处理桶
```

### 4.4 类别代号约定

- 临时指令的 `类别代号` 应以 `TEMP_` 开头
- 该前缀仅用于**人工识别**和**可能的查询过滤**，系统不硬编码检查此前缀
- 系统通过 `指令类型='T'` 判断是否为临时指令

---

## 5. 边界情况

### 5.1 JSON 解析失败

`TempConfigFactory.build()` 抛 `TransferException("TEMP_MISSING_FIELD")`，被 `BatchDispatcher` 或 `TransferService` 捕获，调用 `commandRepository.markErrorWithResult()` 置 E + 写结果。

### 5.2 缺少必填字段

缺少 `scenario` / `dbName` / `tableName` / `ftpName` / `filePath` / `parserType` 之一时，工厂方法抛异常，同上处理。

### 5.3 枚举值非法

`scenario` 或 `emptyDataHandling` 传了不存在的枚举名时，`valueOf()` 抛 `IllegalArgumentException`，转为 `TransferException`。

### 5.4 T 类型 DOWNLOAD_MULTI_NODE：S 子命令查不到主命令

S 子命令处理时，主命令可能已被删除或尚未入库。`resolveTempConfig` 返回 null，桶处理按现有逻辑走 error 路径（`detailRepository.updateStatus` 置 E），不影响整体流程。

### 5.5 T 类型 DOWNLOAD_MULTI_NODE：ChildCommandMonitor 超时

`ChildCommandMonitor` 中的 TOTAL_FLAG 生成依赖 config。如果此时查不到主命令（T 类型），config 为 null，跳过 TOTAL_FLAG 生成，主命令状态更新不受影响（状态更新不依赖 config）。

### 5.6 超时释放

T 类型指令在超时后，`releaseTimeoutTasks()` 正常将其置 E + 写结果（结果表走 `insertSimple` 写入类别/控制代号和描述）。JSON 参数的丢失不会影响超时处理。

### 5.7 启动恢复

`StartupService` 启动时释放本节点残留的 P 状态命令。T 类型指令的 P 状态也会被正常释放，不依赖 temp_config 内容。

---

## 6. 变更文件汇总

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `enums/CommandType.java` | 修改 | 新增 `TEMPORARY("T")` |
| `util/ColumnNames.java` | 修改 | 新增 `TEMP_CONFIG` 常量 |
| `model/Command.java` | 修改 | 新增 `tempConfig` 字段 |
| `repository/CommandRepository.java` | 修改 | findReadyCommands / findById 查询 `temp_config` 列 |
| `transfer/TempTransferConfigFactory.java` | **新增** | `command.tempConfig` JSON → TransferConfig |
| `polling/SerialConstraintChecker.java` | 修改 | T 类型跳过串行约束 |
| `polling/BatchDispatcher.java` | 修改 | T 类型走 TempConfigFactory |
| `transfer/TransferService.java` | 修改 | T 类型走 TempConfigFactory |
| `polling/DetailPollingService.java` | 修改 | S 子命令查不到配置时回溯 T 类型主命令 |
| `transfer/download/ChildCommandMonitor.java` | 修改 | TOTAL_FLAG 阶段回溯 T 类型主命令 |

**零变更**的文件：`PollingService`、`AbstractTransferOrchestrator`、所有 6 个 Handler、`Result`、`TransferSupport`、`UploadSupport`、`DownloadSupport`、`ConfigLoaderService`、`FlagFileService`、`BucketDistributor`、`ResultRepository`、各非 CommandRepository。

---

## 7. 测试要点

- T 类型指令的完整上下传链路（SINGLE / MULTI / BATCH 组合）
- JSON 缺少必填字段 → 正常置 E
- JSON 含非法枚举值 → 正常置 E
- T 类型指令与普通指令同 `类别代号+控制代号` 时并行执行（不阻塞）
- T 类型指令超时释放正常
- 结果表写入正常（ftpName/filePath/dbInfo 从 JSON 取值）
- T 类型 + DOWNLOAD_MULTI_NODE：S 子命令正确从主命令 temp_config 获取配置
- T 类型 + DOWNLOAD_MULTI_NODE：S 子命令桶处理正常
- T 类型 + DOWNLOAD_MULTI_NODE：ChildCommandMonitor 正确生成 TOTAL_FLAG
- T 类型 + DOWNLOAD_MULTI_NODE：主命令被删除时 S 子命令优雅降级
