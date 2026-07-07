# transfer 包精简 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 transfer 包从 25 个类精简至 20 个类，纯结构变化，功能零改动。

**Architecture:** 删除 UploadHandler/DownloadHandler 标记接口 → 6 个 Handler 直接实现 TransferHandler。合并 UploadOrchestrator + DownloadOrchestrator 为单一 TransferOrchestrator，路由从 supports() 遍历改为显式 switch。内联 DirectoryUploadTask 为内部类。内联 TransferUtils 的两个静态方法到 TransferSupport / DownloadSupport。

**Tech Stack:** Java 21, Spring Boot 3.2.5, 纯结构重构

---

## 文件变更总览

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `transfer/TransferHandler.java` | 删除 `supports()` 方法 |
| 新增 | `transfer/TransferOrchestrator.java` | 合并后的单一编排器 |
| 修改 | `transfer/TransferSupport.java` | 添加 `splitFieldValues()` + `determineMainStatus()` |
| 修改 | `transfer/TransferService.java` | 改用 TransferOrchestrator |
| 修改 | `transfer/FieldMappingBuilder.java` | 改 import TransferUtils → TransferSupport |
| 修改 | `transfer/upload/UploadSupport.java` | determineMainStatus 委托给 TransferSupport |
| 修改 | `transfer/upload/SingleUploadHandler.java` | implements TransferHandler 替代 UploadHandler |
| 修改 | `transfer/upload/MultiDirectoryUploadHandler.java` | 内联 DirectoryUploadTask + implements TransferHandler |
| 修改 | `transfer/upload/MultiBatchUploadHandler.java` | implements TransferHandler 替代 UploadHandler |
| 修改 | `transfer/download/DownloadSupport.java` | 添加 `rollbackAfterPostAuditFailure()` |
| 修改 | `transfer/download/SingleDownloadHandler.java` | implements TransferHandler + 改 TransferUtils 引用 |
| 修改 | `transfer/download/SingleNodeDownloadHandler.java` | implements TransferHandler + 改 TransferUtils 引用 |
| 修改 | `transfer/download/MultiNodeDownloadHandler.java` | implements TransferHandler 替代 DownloadHandler |
| 删除 | `transfer/upload/UploadHandler.java` | 标记接口 |
| 删除 | `transfer/download/DownloadHandler.java` | 标记接口 |
| 删除 | `transfer/upload/UploadOrchestrator.java` | 合并为 TransferOrchestrator |
| 删除 | `transfer/download/DownloadOrchestrator.java` | 合并为 TransferOrchestrator |
| 删除 | `transfer/upload/DirectoryUploadTask.java` | 变为内部类 |
| 删除 | `transfer/TransferUtils.java` | 方法内联到 TransferSupport / DownloadSupport |

---

### Task 1: 内联 TransferUtils 方法到 TransferSupport / DownloadSupport

**Files:**
- Modify: `src/main/java/com/fmsy/transfer/TransferSupport.java`
- Modify: `src/main/java/com/fmsy/transfer/download/DownloadSupport.java`
- Modify: `src/main/java/com/fmsy/transfer/FieldMappingBuilder.java`
- Modify: `src/main/java/com/fmsy/transfer/download/SingleDownloadHandler.java`
- Modify: `src/main/java/com/fmsy/transfer/download/SingleNodeDownloadHandler.java`
- Delete: `src/main/java/com/fmsy/transfer/TransferUtils.java`

- [ ] **Step 1: 添加 `splitFieldValues` 到 TransferSupport**

在 `TransferSupport.java` 中 `buildContext` 方法之前添加静态方法：

```java
/**
 * 将两个逗号分隔字符串按位置一一对应解析为 Map。
 * 例如: splitFieldValues("REGION,STATUS", "EAST,ACTIVE")
 * → {"REGION" -> "EAST", "STATUS" -> "ACTIVE"}
 */
public static Map<String, String> splitFieldValues(String names, String values) {
    Map<String, String> result = new LinkedHashMap<>();
    if (names == null || names.isEmpty() || values == null || values.isEmpty()) {
        return result;
    }
    String[] nameArr = names.split(",");
    String[] valueArr = values.split(",");
    int len = Math.min(nameArr.length, valueArr.length);
    for (int i = 0; i < len; i++) {
        String n = nameArr[i].trim();
        String v = valueArr[i].trim();
        if (!n.isEmpty()) {
            result.put(n, v);
        }
    }
    return result;
}
```

需要添加 import: `import java.util.LinkedHashMap;`

在 `buildContext` 方法中将 `TransferUtils.splitFieldValues(splitFields, fieldValue)` 改为 `splitFieldValues(splitFields, fieldValue)`（同类的静态方法，无需限定）。

- [ ] **Step 2: 添加 `rollbackAfterPostAuditFailure` 到 DownloadSupport**

在 `DownloadSupport.java` 末尾添加静态方法：

```java
/**
 * 后审计失败回滚 - 删除 FTP 上已生成的目标文件。
 * best-effort:删除失败仅记 warn,不抛异常。
 */
public static boolean rollbackAfterPostAuditFailure(FtpClient client, String filePath, String reason) {
    if (client == null || filePath == null || filePath.isEmpty()) {
        return false;
    }
    try {
        boolean deleted = client.deleteFile(filePath);
        if (deleted) {
            log.error("Post-audit failed ({}), rolled back FTP file: {}", reason, filePath);
        } else {
            log.error("Post-audit failed ({}), FTP file does not exist or delete denied: {}",
                    reason, filePath);
        }
        return deleted;
    } catch (Exception e) {
        log.error("Failed to delete FTP file {} during post-audit rollback: {}",
                filePath, e.getMessage());
        return false;
    }
}
```

需要添加 import: `import com.fmsy.ftp.FtpClient;`

- [ ] **Step 3: 更新 FieldMappingBuilder 的 import**

`FieldMappingBuilder.java`:
```
// 删除:
import com.fmsy.transfer.TransferUtils;
// 改为:
import com.fmsy.transfer.TransferSupport;
```

调用处 `TransferUtils.splitFieldValues(...)` → `TransferSupport.splitFieldValues(...)`。

- [ ] **Step 4: 更新 SingleDownloadHandler 的引用**

`SingleDownloadHandler.java`:
```
// 删除:
import com.fmsy.transfer.TransferUtils;
```

调用处 `TransferUtils.rollbackAfterPostAuditFailure(...)` → `support.rollbackAfterPostAuditFailure(...)`（support 是 DownloadSupport 的注入字段，但注意该方法是静态的，改为 `DownloadSupport.rollbackAfterPostAuditFailure(...)` 更清晰，或者直接用 `support` 实例调用——Java 允许实例调用静态方法，但推荐类名调用）。

改为: `DownloadSupport.rollbackAfterPostAuditFailure(...)`。

- [ ] **Step 5: 更新 SingleNodeDownloadHandler 的引用**

`SingleNodeDownloadHandler.java`:
```
// 删除:
import com.fmsy.transfer.TransferUtils;
```

调用处 `TransferUtils.rollbackAfterPostAuditFailure(...)` → `DownloadSupport.rollbackAfterPostAuditFailure(...)`。

- [ ] **Step 6: 删除 TransferUtils.java**

```bash
rm src/main/java/com/fmsy/transfer/TransferUtils.java
```

---

### Task 2: 内联 DirectoryUploadTask 为 MultiDirectoryUploadHandler 的内部类

**Files:**
- Modify: `src/main/java/com/fmsy/transfer/upload/MultiDirectoryUploadHandler.java`
- Delete: `src/main/java/com/fmsy/transfer/upload/DirectoryUploadTask.java`

- [ ] **Step 1: 将 DirectoryUploadTask 改为 MultiDirectoryUploadHandler 的内部静态类**

在 `MultiDirectoryUploadHandler.java` 末尾添加内部类：

```java
@RequiredArgsConstructor
private static class FileTask implements Callable<Integer> {
    static final int SKIP = -1;
    static final int FAIL = -2;

    private final String filePath;

    @Override
    public Integer call() {
        // 从原 DirectoryUploadTask.call() 复制全部内容
        // 但 ftpPool / ftpName / config / command / transferSupport / uploadSupport / fieldMappingBuilder
        // 改为直接引用宿主类的字段（因为这些字段现在在同级作用域）
    }
}
```

注意:原 `DirectoryUploadTask` 通过构造器接收 8 个字段，内联后直接引用宿主 `MultiDirectoryUploadHandler` 的字段即可，无需再传参。但 `ftpPool`、`ftpName`、`config`、`command`、`transferSupport`、`uploadSupport`、`fieldMappingBuilder` 在宿主类中都是 `private final` 字段，内部类可以直接访问。

将原 `DirectoryUploadTask` 的 `call()` 方法体完整复制到内部类的 `call()` 中，把 `this.ftpPool`、`this.config` 等字段引用直接去掉 `this.`（或保留 `ClassName.this.` 限定），因为内部类可以直接访问外部类字段。

- [ ] **Step 2: 修改 submit 处的引用**

`MultiDirectoryUploadHandler.handle()` 中，原来创建 `DirectoryUploadTask` 的地方改为：

```java
// 原来:
DirectoryUploadTask task = new DirectoryUploadTask(filePath, ftpPool, ftpName,
        config, command, transferSupport, support, fieldMappingBuilder);

// 改为:
FileTask task = new FileTask(filePath);
```

- [ ] **Step 3: 添加需要的 import**

在 `MultiDirectoryUploadHandler.java` 中添加 `import java.util.concurrent.Callable;`（如果还没有）。

- [ ] **Step 4: 删除 DirectoryUploadTask.java**

```bash
rm src/main/java/com/fmsy/transfer/upload/DirectoryUploadTask.java
```

---

### Task 3: 删除标记接口 + 简化 TransferHandler

**Files:**
- Modify: `src/main/java/com/fmsy/transfer/TransferHandler.java`
- Delete: `src/main/java/com/fmsy/transfer/upload/UploadHandler.java`
- Delete: `src/main/java/com/fmsy/transfer/download/DownloadHandler.java`
- Modify: `src/main/java/com/fmsy/transfer/upload/SingleUploadHandler.java`
- Modify: `src/main/java/com/fmsy/transfer/upload/MultiDirectoryUploadHandler.java`
- Modify: `src/main/java/com/fmsy/transfer/upload/MultiBatchUploadHandler.java`
- Modify: `src/main/java/com/fmsy/transfer/download/SingleDownloadHandler.java`
- Modify: `src/main/java/com/fmsy/transfer/download/SingleNodeDownloadHandler.java`
- Modify: `src/main/java/com/fmsy/transfer/download/MultiNodeDownloadHandler.java`

- [ ] **Step 1: 简化 TransferHandler 接口**

删除 `supports()` 方法，仅保留 `handle()`：

```java
public interface TransferHandler {
    void handle(Command command, TransferConfig config, Result result) throws Exception;
}
```

- [ ] **Step 2: 更新 6 个 Handler 的实现声明**

每个 Handler 的 `implements UploadHandler` / `implements DownloadHandler` 改为 `implements TransferHandler`。同时删除每个 Handler 中的 `supports()` 方法（不再需要）。

| Handler | 改前 | 改后 |
|---------|------|------|
| `SingleUploadHandler` | `implements UploadHandler` | `implements TransferHandler` |
| `MultiDirectoryUploadHandler` | `implements UploadHandler` | `implements TransferHandler` |
| `MultiBatchUploadHandler` | `implements UploadHandler` | `implements TransferHandler` |
| `SingleDownloadHandler` | `implements DownloadHandler` | `implements TransferHandler` |
| `SingleNodeDownloadHandler` | `implements DownloadHandler` | `implements TransferHandler` |
| `MultiNodeDownloadHandler` | `implements DownloadHandler` | `implements TransferHandler` |

每个 Handler 需要删除 `supports()` 方法及其 `@Override` 注解。例如 `SingleUploadHandler`：

```
// 删除整个方法块:
@Override
public boolean supports(TransferScenario scenario, CommandType commandType) {
    return scenario == TransferScenario.UPLOAD_SINGLE
            && (commandType == null || commandType == CommandType.SERIAL);
}
```

删除后可能某些 Handler 不再需要 `import com.fmsy.enums.TransferScenario;` 和 `import com.fmsy.enums.CommandType;` —— 检查每个 Handler 的 import，如果 `TransferScenario`/`CommandType` 不再在文件中使用则删除对应的 import。

- [ ] **Step 3: 删除 UploadHandler.java**

```bash
rm src/main/java/com/fmsy/transfer/upload/UploadHandler.java
```

- [ ] **Step 4: 删除 DownloadHandler.java**

```bash
rm src/main/java/com/fmsy/transfer/download/DownloadHandler.java
```

---

### Task 4: 合并 UploadOrchestrator + DownloadOrchestrator 为 TransferOrchestrator

**Files:**
- Modify: `src/main/java/com/fmsy/transfer/AbstractTransferOrchestrator.java`
- Create: `src/main/java/com/fmsy/transfer/TransferOrchestrator.java`
- Delete: `src/main/java/com/fmsy/transfer/upload/UploadOrchestrator.java`
- Delete: `src/main/java/com/fmsy/transfer/download/DownloadOrchestrator.java`

- [ ] **Step 1: 修改 AbstractTransferOrchestrator**

删除 `direction` 构造函数参数，改为从 `TransferScenario` 推导方向：

```java
@Slf4j
public abstract class AbstractTransferOrchestrator {

    private final CommandRepository commandRepository;
    private final ResultRepository resultRepository;
    private final ChildCommandMonitor childCommandMonitor;
    private final DataSourceConfig.DbPool dbPool;
    // 删除: private final String direction;

    protected AbstractTransferOrchestrator(CommandRepository commandRepository,
                                           ResultRepository resultRepository,
                                           ChildCommandMonitor childCommandMonitor,
                                           DataSourceConfig.DbPool dbPool) {
        this.commandRepository = commandRepository;
        this.resultRepository = resultRepository;
        this.childCommandMonitor = childCommandMonitor;
        this.dbPool = dbPool;
    }

    public final void execute(Command command, TransferConfig config) {
        log.info("Executing {} for command: {}", config.getScenario(), command.getId());
        Result result = newResult(config.getScenario());
        // ... 其余不变
    }

    private Result newResult(TransferScenario scenario) {
        String direction = scenario.name().startsWith("UPLOAD")
                ? Result.DIRECTION_UPLOAD : Result.DIRECTION_DOWNLOAD;
        return Result.builder()
                .transferDirection(direction)
                .markStart()
                .build();
    }
    
    // 其余方法不变
}
```

需要添加 import: `import com.fmsy.enums.TransferScenario;`

删除 import: `import com.fmsy.model.Result;`（如果 newResult 方法移到抽象类后不再需要... 实际上仍然需要 Result 来构建，所以保留）

Wait, `Result.DIRECTION_UPLOAD` 和 `Result.DIRECTION_DOWNLOAD` 已经在用了，所以 `import com.fmsy.model.Result;` 仍然需要。

- [ ] **Step 2: 创建 TransferOrchestrator.java**

```java
package com.fmsy.transfer;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.TransferScenario;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.transfer.download.*;
import com.fmsy.transfer.upload.*;
import org.springframework.stereotype.Service;

/**
 * 传输编排器 — 按 (scenario, commandType) 显式路由到对应 Handler。
 *
 * <p>合并自原 UploadOrchestrator + DownloadOrchestrator,路由方式从 supports() 遍历
 * 改为显式 switch,避免标记接口开销。加新场景只需在本类 dispatch 加一行 case 即可。
 *
 * <p>COORDINATED(S) 类型的 DOWNLOAD_MULTI_NODE 命令不由本类处理,
 * 而是由 TransferService 直接转给 DetailPollingService。
 */
@Service
public class TransferOrchestrator extends AbstractTransferOrchestrator {

    private final SingleUploadHandler singleUpload;
    private final MultiDirectoryUploadHandler multiDirUpload;
    private final MultiBatchUploadHandler multiBatchUpload;
    private final SingleDownloadHandler singleDownload;
    private final SingleNodeDownloadHandler singleNodeDownload;
    private final MultiNodeDownloadHandler multiNodeDownload;

    public TransferOrchestrator(SingleUploadHandler singleUpload,
                                MultiDirectoryUploadHandler multiDirUpload,
                                MultiBatchUploadHandler multiBatchUpload,
                                SingleDownloadHandler singleDownload,
                                SingleNodeDownloadHandler singleNodeDownload,
                                MultiNodeDownloadHandler multiNodeDownload,
                                CommandRepository commandRepository,
                                ResultRepository resultRepository,
                                ChildCommandMonitor childCommandMonitor,
                                DataSourceConfig.DbPool dbPool) {
        super(commandRepository, resultRepository, childCommandMonitor, dbPool);
        this.singleUpload = singleUpload;
        this.multiDirUpload = multiDirUpload;
        this.multiBatchUpload = multiBatchUpload;
        this.singleDownload = singleDownload;
        this.singleNodeDownload = singleNodeDownload;
        this.multiNodeDownload = multiNodeDownload;
    }

    @Override
    protected void dispatch(Command command, TransferConfig config, Result result) throws Exception {
        TransferScenario scenario = config.getScenario();
        CommandType commandType = command.getCommandType();

        switch (scenario) {
            case UPLOAD_SINGLE:
                singleUpload.handle(command, config, result);
                break;
            case UPLOAD_MULTI:
                if (commandType == CommandType.BATCH) {
                    multiBatchUpload.handle(command, config, result);
                } else {
                    multiDirUpload.handle(command, config, result);
                }
                break;
            case DOWNLOAD_SINGLE:
                singleDownload.handle(command, config, result);
                break;
            case DOWNLOAD_SINGLE_NODE:
                singleNodeDownload.handle(command, config, result);
                break;
            case DOWNLOAD_MULTI_NODE:
                multiNodeDownload.handle(command, config, result);
                break;
            default:
                throw new IllegalArgumentException("No handler for scenario=" + scenario
                        + ", commandType=" + commandType);
        }
    }
}
```

- [ ] **Step 3: 删除 UploadOrchestrator.java**

```bash
rm src/main/java/com/fmsy/transfer/upload/UploadOrchestrator.java
```

- [ ] **Step 4: 删除 DownloadOrchestrator.java**

```bash
rm src/main/java/com/fmsy/transfer/download/DownloadOrchestrator.java
```

---

### Task 5: 更新 TransferService

**Files:**
- Modify: `src/main/java/com/fmsy/transfer/TransferService.java`

- [ ] **Step 1: 改注入和路由逻辑**

```java
// 删除:
import com.fmsy.transfer.download.DownloadOrchestrator;
import com.fmsy.transfer.upload.UploadOrchestrator;

// 添加:
import com.fmsy.transfer.TransferOrchestrator;

// 字段替换:
// 删除:
private final UploadOrchestrator uploadOrchestrator;
private final DownloadOrchestrator downloadOrchestrator;
// 改为:
private final TransferOrchestrator transferOrchestrator;

// 构造器参数替换:
// 删除: UploadOrchestrator uploadOrchestrator, DownloadOrchestrator downloadOrchestrator
// 改为: TransferOrchestrator transferOrchestrator

// process 方法中的调用改为:
// 原来的:
// if (Result.DIRECTION_UPLOAD.equals(direction)) {
//     uploadOrchestrator.execute(command, config);
// } else if (Result.DIRECTION_DOWNLOAD.equals(direction)) {
//     if (command.getCommandType() == CommandType.COORDINATED) {
//         ...detailPollingService...
//     } else {
//         downloadOrchestrator.execute(command, config);
//     }
// }
// 改为:
if (Result.DIRECTION_DOWNLOAD.equals(direction) && command.getCommandType() == CommandType.COORDINATED) {
    String extraInfo = command.getExtraInfo();
    String mainCommandId = extraInfo != null && extraInfo.contains("|")
            ? extraInfo.substring(0, extraInfo.indexOf('|'))
            : extraInfo;
    detailPollingService.pollAndProcess(appConfig.getNodeId(), mainCommandId, command);
} else {
    transferOrchestrator.execute(command, config);
}
```

---

### Task 6: 抽取公共 determineMainStatus 到 TransferSupport

**Files:**
- Modify: `src/main/java/com/fmsy/transfer/TransferSupport.java`
- Modify: `src/main/java/com/fmsy/transfer/upload/UploadSupport.java`
- Modify: `src/main/java/com/fmsy/transfer/download/DownloadSupport.java`

- [ ] **Step 1: 添加 determineMainStatus 到 TransferSupport**

在 `TransferSupport.java` 中添加：

```java
/**
 * 根据成功/失败/跳过计数判定主指令最终状态。
 */
public static String determineMainStatus(boolean allSuccess, int failedCount, int skippedCount) {
    if (allSuccess) return ColumnNames.STATUS_SUCCESS;
    if (failedCount > 0) return ColumnNames.STATUS_ERROR;
    if (skippedCount > 0) return ColumnNames.STATUS_SKIPPED;
    return ColumnNames.STATUS_ERROR;
}
```

需要添加 import: `import com.fmsy.util.ColumnNames;`

- [ ] **Step 2: 修改 UploadSupport.determineMainStatus 委托给公共方法**

```java
public static String determineMainStatus(UploadResult result) {
    boolean allSuccess = result.failedCount() == 0 && result.skippedCount() == 0;
    return TransferSupport.determineMainStatus(allSuccess, result.failedCount(), result.skippedCount());
}
```

- [ ] **Step 3: 修改 DownloadSupport.determineMainStatus 委托给公共方法**

```java
public static String determineMainStatus(boolean allSuccess, int failedCount, int skippedCount) {
    return TransferSupport.determineMainStatus(allSuccess, failedCount, skippedCount);
}

public static String determineMainStatus(BucketSummary summary) {
    return determineMainStatus(summary.allFilesSuccess(), summary.failedCount(), summary.skippedCount());
}
```

---

### Task 7: 编译验证

- [ ] **Step 1: 运行全量编译**

```bash
cd D:\Project\FMSY && .\gradlew.bat compileJava --no-daemon
```

预期: BUILD SUCCESSFUL。如果有编译错误，根据错误信息修复。

常见问题检查清单:
- 所有 Handler 的 `implements` 已改为 `implements TransferHandler`
- 所有 Handler 的 `supports()` 方法已删除
- 不再引用的 import 已清理
- `TransferService` 的 `transferOrchestrator` 字段和构造器已更新
- `FieldMappingBuilder` 的 import 已更新
- `SingleDownloadHandler` / `SingleNodeDownloadHandler` 的 `TransferUtils` 引用已替换
- `AbstractTransferOrchestrator` 构造器不再传 direction

- [ ] **Step 2: 确认删除文件已不存在**

```bash
ls src/main/java/com/fmsy/transfer/TransferUtils.java 2>&1 || echo "已删除"
ls src/main/java/com/fmsy/transfer/upload/DirectoryUploadTask.java 2>&1 || echo "已删除"
ls src/main/java/com/fmsy/transfer/upload/UploadHandler.java 2>&1 || echo "已删除"
ls src/main/java/com/fmsy/transfer/download/DownloadHandler.java 2>&1 || echo "已删除"
ls src/main/java/com/fmsy/transfer/upload/UploadOrchestrator.java 2>&1 || echo "已删除"
ls src/main/java/com/fmsy/transfer/download/DownloadOrchestrator.java 2>&1 || echo "已删除"
```

预期: 全部返回 "已删除"。

- [ ] **Step 3: 确认新增文件已存在**

```bash
ls src/main/java/com/fmsy/transfer/TransferOrchestrator.java
```

预期: 文件存在。

- [ ] **Step 4: 最终计数确认**

```bash
find src/main/java/com/fmsy/transfer -name "*.java" | wc -l
```

预期: 20（从 25 减少到 20）。

---

## 自检清单

1. **Spec 覆盖**: 设计文档中 5 个精简项(标记接口删除、Orchestrator 合并、DirectoryUploadTask 内联、TransferUtils 内联、determineMainStatus 抽取)均已覆盖。
2. **无占位符**: 所有代码块都包含完整的具体代码。
3. **类型一致性**: TransferHandler 接口删除 supports() 后，6 个 Handler 同步删除 supports()。TransferService 改用 TransferOrchestrator 后路由逻辑改为单一判断。AbstractTransferOrchestrator 删除 direction 参数后，TransferOrchestrator 构造器不再传 direction。
4. **无测试任务**: 根据项目说明(本地无测试环境,只做代码开发不写测试),未安排测试任务。但安排了编译验证确保不破坏现有代码。
