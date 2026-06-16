# 临时指令实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持一种指令类型为 `T` 的临时指令，所有传输参数以 JSON 存放在 `temp_config` 列，不依赖传输配置表，同时支持 DOWNLOAD_MULTI_NODE 场景。

**Architecture:** 在指令表新增 `temp_config` 列，T 类型指令的 `Command` 实体携带此字段。新增 `TempTransferConfigFactory` 从 JSON 构建 `TransferConfig`。在 `BatchDispatcher` / `TransferService` 中 T 类型分支走工厂；`DetailPollingService` 和 `ChildCommandMonitor` 在查不到配置时回溯 T 类型主命令。

**Tech Stack:** Spring Boot, JdbcTemplate, Lombok, ParserConfigUtil

---

### Task 1: CommandType 新增 TEMPORARY 枚举

**Files:**
- Modify: `src/main/java/com/fmsy/enums/CommandType.java`

- [ ] **Step 1: 新增 TEMPORARY("T") 枚举值**

```java
// 在 COORDINATED("S") 后追加
TEMPORARY("T");   // 新增
```

- [ ] **Step 2: 验证 fromCode 能正确识别 "T"**

```java
// 确认 fromCode("T") == TEMPORARY
// 现有循环 for (CommandType type : values()) 会遍历新值，无需修改 fromCode 方法
// 确认 code() 返回 "T"：TEMPORARY.code() -> "T"
```

---

### Task 2: ColumnNames 和 Command 实体新增 tempConfig 字段

**Files:**
- Modify: `src/main/java/com/fmsy/util/ColumnNames.java`
- Modify: `src/main/java/com/fmsy/model/Command.java`

- [ ] **Step 1: ColumnNames 新增 TEMP_CONFIG 常量**

```java
// 在结果表区域后、状态值区域前插入
// temp_config 列(仅 T 类型有值)
public static final String TEMP_CONFIG = "temp_config";
```

- [ ] **Step 2: Command 实体新增 tempConfig 字段**

```java
// 在 extraInfo 字段后追加
/** 临时指令 JSON 配置（仅指令类型='T' 时有值，存于 temp_config 列） */
private String tempConfig;
```

---

### Task 3: CommandRepository 查询 temp_config 列

**Files:**
- Modify: `src/main/java/com/fmsy/repository/CommandRepository.java`

- [ ] **Step 1: findReadyCommands 增加 temp_config 列的查询和映射**

```java
// SQL_FIND_READY_COMMANDS 的 SELECT 列表中追加
// ", " + ColumnNames.TEMP_CONFIG

// 完整 SQL（改动部分为 SELECT 列表和 RowMapper）：
private static final String SQL_FIND_READY_COMMANDS =
    "SELECT " + ColumnNames.ID + ", " + ColumnNames.CATEGORY_CODE + ", " +
    ColumnNames.CONTROL_CODE + ", " + ColumnNames.COMMAND_TYPE + ", " +
    ColumnNames.AUDIT_COUNT + ", " + ColumnNames.EXTRA_INFO + ", " +
    ColumnNames.TEMP_CONFIG +
    " FROM (SELECT " + ColumnNames.ID + ", " + ColumnNames.CATEGORY_CODE + ", " +
    ColumnNames.CONTROL_CODE + ", " + ColumnNames.COMMAND_TYPE + ", " +
    ColumnNames.AUDIT_COUNT + ", " + ColumnNames.EXTRA_INFO + ", " +
    ColumnNames.TEMP_CONFIG + ", " +
    "ROW_NUMBER() OVER (PARTITION BY " + ColumnNames.CATEGORY_CODE + ", " +
    ColumnNames.CONTROL_CODE + " ORDER BY " + ColumnNames.ID + " ASC) AS rn" +
    " FROM " + TableNames.COMMAND_TABLE + " WHERE " + ColumnNames.PROCESS_STATUS + "=? AND " +
    ColumnNames.PROCESSING_NODE + " IS NULL) t WHERE rn = 1 ORDER BY " + ColumnNames.ID + " ASC LIMIT ?";

// RowMapper 中追加：
cmd.setTempConfig(rs.getString(ColumnNames.TEMP_CONFIG));
```

- [ ] **Step 2: findById 增加 temp_config 列的查询和映射**

```java
// SQL_FIND_BY_ID 的 SELECT 列表中追加
// ", " + ColumnNames.TEMP_CONFIG

private static final String SQL_FIND_BY_ID =
    "SELECT " + ColumnNames.ID + ", " + ColumnNames.CATEGORY_CODE + ", " +
    ColumnNames.CONTROL_CODE + ", " + ColumnNames.COMMAND_TYPE + ", " +
    ColumnNames.AUDIT_COUNT + ", " + ColumnNames.EXTRA_INFO + ", " +
    ColumnNames.TEMP_CONFIG +
    " FROM " + TableNames.COMMAND_TABLE + " WHERE " + ColumnNames.ID + "=?";

// 映射代码中追加：
cmd.setTempConfig((String) row.get(ColumnNames.TEMP_CONFIG));
```

---

### Task 4: 新增 TempTransferConfigFactory

**Files:**
- Create: `src/main/java/com/fmsy/transfer/TempTransferConfigFactory.java`

- [ ] **Step 1: 创建工厂类，实现 JSON → TransferConfig 解析**

```java
package com.fmsy.transfer;

import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.enums.TransferScenario;
import com.fmsy.exception.TransferException;
import com.fmsy.model.Command;
import com.fmsy.model.TransferConfig;
import com.fmsy.util.ParserConfigUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 临时指令配置工厂 — 将 T 类型指令的 temp_config JSON 解析为 TransferConfig 实体。
 *
 * <p>由 {@link BatchDispatcher} / {@link TransferService} 在检测到
 * {@link com.fmsy.enums.CommandType#TEMPORARY} 时调用。
 * S 子命令回溯 T 类型主命令时也通过本工厂构建配置。
 *
 * <p>JSON 字段映射见 docs/superpowers/specs/2026-06-16-temp-command-design.md §3.6。
 */
@Slf4j
@Component
public class TempTransferConfigFactory {

    /**
     * 从 command.tempConfig JSON 构建 TransferConfig。
     *
     * @param command 指令（应为 TEMPORARY 类型）
     * @return 完整的 TransferConfig
     * @throws TransferException JSON 解析失败或缺少必填字段时抛出
     */
    public TransferConfig build(Command command) {
        String json = command.getTempConfig();
        if (json == null || json.trim().isEmpty()) {
            throw new TransferException("TEMP_MISSING_FIELD",
                    "temp_config is null or empty for command: " + command.getId());
        }
        Map<String, String> map = ParserConfigUtil.parseJson(json);
        if (map.isEmpty()) {
            throw new TransferException("TEMP_PARSE_FAILED",
                    "Failed to parse temp_config JSON for command: " + command.getId());
        }

        TransferConfig config = new TransferConfig();
        config.setCategoryCode(command.getCategoryCode());
        config.setControlCode(command.getControlCode());

        // 必填字段
        config.setScenario(requireEnum(map, "scenario", TransferScenario.class, command.getId()));
        config.setDbName(require(map, "dbName", command.getId()));
        config.setTableName(require(map, "tableName", command.getId()));
        config.setFtpName(require(map, "ftpName", command.getId()));
        config.setFilePath(require(map, "filePath", command.getId()));
        config.setParserType(require(map, "parserType", command.getId()));

        // 可选字段（有默认值）
        config.setClearTableFlag(map.getOrDefault("clearTableFlag", "N"));
        config.setOverwriteFlag(map.getOrDefault("overwriteFlag", "Y"));
        config.setSerialFlag(map.getOrDefault("serialFlag", "N"));

        // 可选字段（无默认值，可为 null）
        config.setNodeId(map.get("nodeId"));
        config.setParserConfig(map.get("parserConfig"));
        config.setPreOperations(map.get("preOperations"));
        config.setPostOperations(map.get("postOperations"));
        config.setIgnoreFields(map.get("ignoreFields"));
        config.setSplitFields(map.get("splitFields"));

        // 可选整数
        String concurrencyStr = map.get("concurrency");
        if (concurrencyStr != null && !concurrencyStr.isEmpty()) {
            try {
                config.setConcurrency(Integer.parseInt(concurrencyStr));
            } catch (NumberFormatException e) {
                log.warn("Invalid concurrency value '{}' for command {}, using null", concurrencyStr, command.getId());
            }
        }

        // 可选枚举
        String emptyStr = map.get("emptyDataHandling");
        if (emptyStr != null && !emptyStr.isEmpty()) {
            try {
                config.setEmptyDataHandling(EmptyDataHandling.valueOf(emptyStr));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid emptyDataHandling '{}' for command {}, using ALLOW", emptyStr, command.getId());
            }
        }

        log.info("Built TransferConfig from temp_config for command: {}", command.getId());
        return config;
    }

    private static String require(Map<String, String> map, String key, Long commandId) {
        String val = map.get(key);
        if (val == null || val.trim().isEmpty()) {
            throw new TransferException("TEMP_MISSING_FIELD",
                    "Missing required field '" + key + "' in temp_config for command: " + commandId);
        }
        return val.trim();
    }

    private static <T extends Enum<T>> T requireEnum(Map<String, String> map, String key,
                                                      Class<T> enumClass, Long commandId) {
        String val = require(map, key, commandId);
        try {
            return Enum.valueOf(enumClass, val);
        } catch (IllegalArgumentException e) {
            throw new TransferException("TEMP_INVALID_ENUM",
                    "Invalid " + key + "='" + val + "' for command: " + commandId, e);
        }
    }
}
```

---

### Task 5: SerialConstraintChecker 跳过 T 类型

**Files:**
- Modify: `src/main/java/com/fmsy/polling/SerialConstraintChecker.java`

- [ ] **Step 1: check 方法开头增加 T 类型短路**

```java
public boolean check(Command command, Map<String, CommandProcessingTracker> processingMap) {
    // T 类型临时指令不参与串行约束
    if (command.getCommandType() == CommandType.TEMPORARY) {
        return true;
    }
    // ... 后续原有逻辑不变
}
```

---

### Task 6: BatchDispatcher T 类型分支

**Files:**
- Modify: `src/main/java/com/fmsy/polling/BatchDispatcher.java`

- [ ] **Step 1: 注入 TempTransferConfigFactory**

```java
// 在现有字段后追加
private final TempTransferConfigFactory tempConfigFactory;
```

- [ ] **Step 2: dispatch 方法中 T 类型走 TempConfigFactory**

```java
// 原代码（约第 82 行）：
TransferConfig config = configLoader.getConfigOrDefault(cmd.getCategoryCode(), cmd.getControlCode());

// 改为：
TransferConfig config;
if (cmd.getCommandType() == com.fmsy.enums.CommandType.TEMPORARY) {
    config = tempConfigFactory.build(cmd);
} else {
    config = configLoader.getConfigOrDefault(cmd.getCategoryCode(), cmd.getControlCode());
}
```

---

### Task 7: TransferService T 类型分支

**Files:**
- Modify: `src/main/java/com/fmsy/transfer/TransferService.java`

- [ ] **Step 1: 注入 TempTransferConfigFactory**

```java
// 在现有字段后追加
private final TempTransferConfigFactory tempConfigFactory;
```

- [ ] **Step 2: process 方法中 T 类型走 TempConfigFactory**

```java
// 原代码（约第 66 行）：
TransferConfig config = configLoader.getConfigOrDefault(
    command.getCategoryCode(), command.getControlCode());

// 改为：
TransferConfig config;
if (command.getCommandType() == com.fmsy.enums.CommandType.TEMPORARY) {
    config = tempConfigFactory.build(command);
} else {
    config = configLoader.getConfigOrDefault(
        command.getCategoryCode(), command.getControlCode());
}
```

---

### Task 8: DetailPollingService 支持 T 类型主命令回溯

**Files:**
- Modify: `src/main/java/com/fmsy/polling/DetailPollingService.java`

- [ ] **Step 1: 注入 CommandRepository 和 TempTransferConfigFactory**

```java
// 在现有字段中追加：
private final CommandRepository commandRepository;
private final TempTransferConfigFactory tempConfigFactory;
```

- [ ] **Step 2: 新增 resolveTempConfig 辅助方法**

```java
/**
 * 解析 S 子命令对应的 TransferConfig。
 * 优先查传输配置表，若查不到且主命令是 T 类型，则从主命令的 temp_config 构建。
 *
 * @param subCommand S 型子命令
 * @return TransferConfig，完全查不到时返回 null
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
        log.warn("Cannot resolve main command id from extraInfo: {}", extraInfo);
        return null;
    }
    String mainIdStr = extraInfo.substring(0, extraInfo.indexOf('|'));
    try {
        Command mainCommand = commandRepository.findById(Long.parseLong(mainIdStr));
        if (mainCommand != null && mainCommand.getCommandType() == CommandType.TEMPORARY) {
            log.info("Resolved config from T-type main command: {}", mainCommand.getId());
            return tempConfigFactory.build(mainCommand);
        }
    } catch (Exception e) {
        log.warn("Failed to resolve temp config from main command: {}", e.getMessage());
    }
    return null;
}
```

- [ ] **Step 3: writeSubCommandResult 中使用 resolveTempConfig**

```java
// 原代码（约第 181 行）：
TransferConfig config = configLoader.getConfigOrDefault(
    subCommand.getCategoryCode(), subCommand.getControlCode());

// 改为：
TransferConfig config = resolveTempConfig(subCommand);
```

- [ ] **Step 4: processBucket 中使用 resolveTempConfig**

这个方法接收 `Detail bucket` 而非 `Command`，需要先找到子命令。最简单的方式是在 `pollAndProcess` 入口处一次性解析 config 作为成员变量传递。

修改方案：在 `pollAndProcess` 中提前解析 config，传入 `runOneBucket` 和 `processBucket`：

```java
// pollAndProcess 中新增（在 while 循环前）：
TransferConfig mainConfig = resolveTempConfig(subCommand);
if (mainConfig == null) {
    log.error("Cannot resolve config for sub-command {}, marking ERROR", subCommand.getId());
    writeSubCommandResult(subCommand, startTime, 0, 0, 1, 0);
    return;
}

// 修改 runOneBucket 签名，增加 config 参数：
// runOneBucket(bucket, nodeId, mainConfig, successCount, failedCount, skippedCount)

// 修改 processBucket 签名，移除内部的 configLoader 调用，直接使用传入的 config：
// processBucket(bucket, nodeId, mainConfig)
```

在 `processBucket` 中删除原有的：

```java
TransferConfig config = configLoader.getConfigOrDefault(
    bucket.getCategoryCode(), bucket.getControlCode());
if (config == null) {
    log.error("No config found for bucket: ...");
    detailRepository.updateStatus(bucket.getId(), ColumnNames.STATUS_ERROR, nodeId);
    return BucketOutcome.FAILED;
}
```

改为直接使用传入的 `config` 参数。

---

### Task 9: ChildCommandMonitor 支持 T 类型 TOTAL_FLAG

**Files:**
- Modify: `src/main/java/com/fmsy/transfer/download/ChildCommandMonitor.java`

- [ ] **Step 1: 注入 CommandRepository 和 TempTransferConfigFactory**

```java
// 在现有字段中追加：
private final CommandRepository commandRepository;
private final TempTransferConfigFactory tempConfigFactory;
```

- [ ] **Step 2: updateMainCommandStatus 中 TOTAL_FLAG 阶段增加 T 类型回溯**

```java
// 原代码（约第 196 行）：
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

---

### Task 10: 数据库迁移 SQL

**Files:**
- Create: `docs/migrations/V20260616_add_temp_config_column.sql`

- [ ] **Step 1: 编写 ALTER TABLE SQL**

```sql
-- 指令表新增 temp_config 列，用于 T 类型临时指令的 JSON 参数
ALTER TABLE 指令表 ADD COLUMN temp_config TEXT;
COMMENT ON COLUMN 指令表.temp_config IS '临时指令配置(JSON)，仅指令类型=T时有值';
```
