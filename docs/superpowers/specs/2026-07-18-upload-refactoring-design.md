# 上传处理重构设计

## 概述

消除单文件上传（UPLOAD_SINGLE）和目录文件上传（UPLOAD_MULTI）之间的代码重复，包括上传管线编排和标志文件路径操作两个领域。

## 改动范围

涉及 5 个文件，均为删除/简化/迁移，不新增类。

| 文件 | 改动 |
|------|------|
| `UploadSupport.java` | 新增 `executeFilePipeline` + `safeExecuteFilePipeline`；删除 `expandPathVariables` 包装；删除 `normalizePathSlashes`；`extractFlagPathPattern` 迁移到 `FlagFileService` |
| `SingleUploadHandler.java` | `handleSingle`/`handleBatch` 改为调用 `safeExecuteFilePipeline`，消除重复管线 + 异常处理 |
| `MultiUploadHandler.java` | `insertSingleFile` 改为调用 `safeExecuteFilePipeline`；`resolveFlagName` 改为使用 `ResolvedPath.resolveRelative`；删除 `fileNameOnly` |
| `FlagFileService.java` | `ContentEngine.expandPathVariables` 改为委托 `TransferSupport`；新增 `extractFlagPathPattern`（从 `UploadSupport` 迁入） |
| `TransferSupport.java` | 不变（`expandPathVariables` 已是 public static） |

## 1. 上传管线收敛

### 1.1 新增 `executeFilePipeline`（UploadSupport）

```java
/**
 * 单文件上传管线 — preCheck → insertDataAndVerify → postProcess。
 * <p>
 * 不包含 truncateTable（由调用方在适当时机执行）。
 * mapping 和 detailContext 二选一：
 * <ul>
 *   <li>mapping != null — 使用预构建的 FieldMapping（多文件并行场景）</li>
 *   <li>detailContext != null — 使用明细行上下文构建 FieldMapping（BATCH 场景）</li>
 *   <li>两者都 null — 自动构建 FieldMapping（SERIAL 单文件场景）</li>
 * </ul>
 */
public UploadResult executeFilePipeline(FtpClient client, TransferConfig config,
        ResolvedPath fileInfo, String filePath,
        FieldMapping mapping, Map<String, Object> detailContext,
        Integer auditCount) {

    // Phase 1: preCheck
    UploadResult checkResult = preCheck(client, config, fileInfo, filePath);
    if (checkResult != null) return checkResult;

    // Phase 2: insert + verify
    int count = (mapping != null)
        ? insertDataAndVerify(client, config, mapping, filePath, auditCount)
        : insertDataAndVerify(client, config, fileInfo, detailContext, filePath, auditCount);

    // Phase 3: postProcess
    postProcess(client, config, fileInfo, count);

    return new UploadResult(count, 1, 0, 0, null);
}
```

### 1.2 新增 `safeExecuteFilePipeline`（UploadSupport）

```java
/**
 * 安全版上传管线 — 自动管理 FTP 连接生命周期 + 异常时迁移文件到 error 目录。
 * <p>
 * 返回 UploadResult 而非抛出异常，调用方根据 status 判断结果：
 * <ul>
 *   <li>status=null — 成功</li>
 *   <li>status=SKIPPED — preCheck 未通过（标志文件不存在）</li>
 *   <li>status=ERROR — 异常，文件已迁移到 error 目录</li>
 * </ul>
 */
public UploadResult safeExecuteFilePipeline(String ftpName, String filePath,
        ResolvedPath fileInfo, TransferConfig config,
        FieldMapping mapping, Map<String, Object> detailContext,
        Integer auditCount) {
    try {
        return transferSupport.executeWithClient(ftpName, client ->
            executeFilePipeline(client, config, fileInfo, filePath,
                mapping, detailContext, auditCount));
    } catch (FlagCheckException e) {
        moveDataAndFlagToErrorDir(ftpName, filePath, config);
        return new UploadResult(0, 0, 0, 1, ColumnNames.STATUS_ERROR);
    } catch (RuntimeException e) {
        moveDataAndFlagToErrorDir(ftpName, filePath, config);
        return new UploadResult(0, 0, 0, 1, ColumnNames.STATUS_ERROR);
    }
}
```

### 1.3 Handler 调用变化

#### SingleUploadHandler.handleSingle

```java
private void handleSingle(Command command, TransferConfig config, Result result) {
    ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);
    String filePath = fileInfo.fullPath();

    // 清表（单文件场景，在 FTP 连接外执行）
    if (BooleanUtils.isYes(config.getClearTableFlag())) {
        support.truncateTable(config);
    }

    var r = support.safeExecuteFilePipeline(
            config.getFtpName(), filePath, fileInfo, config,
            null, null, command.getAuditCount());

    if (r.status() != null) {
        result.setOutcome(0, r.status(), "Upload " + r.status());
        return;
    }
    result.setOutcome(r.records(), ColumnNames.STATUS_SUCCESS, "");
}
```

#### SingleUploadHandler.handleBatch

```java
private void handleBatch(Command command, TransferConfig config, Result result) {
    // 加载明细、构建路径、取 auditCount（不变）
    List<Map<String, Object>> details = detailRepository.findUploadDetails(
            command.getId(), ColumnNames.STATUS_EMPTY);
    if (details.isEmpty()) { /* 同前 */ return; }

    Map<String, Object> detail = details.get(0);
    Long detailId = ((Number) detail.get(ColumnNames.DETAIL_ID)).longValue();
    String fileName = (String) detail.get(ColumnNames.FILE_NAME);
    if (fileName == null || fileName.isEmpty()) { /* 同前 */ return; }

    String detailFieldName = (String) detail.get(ColumnNames.FIELD_NAME);
    String detailFieldValue = (String) detail.get(ColumnNames.FIELD_VALUE);
    Map<String, String> pathContext = transferSupport.buildContext(
            command, detailFieldName, detailFieldValue);
    pathContext.put("FILE_NAME", fileName);
    ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), pathContext);
    String filePath = fileInfo.fullPath();

    Integer auditCount = detail.get(ColumnNames.AUDIT_COUNT) != null
            ? ((Number) detail.get(ColumnNames.AUDIT_COUNT)).intValue() : null;

    // 清表（单文件场景，在 FTP 连接外执行）
    if (BooleanUtils.isYes(config.getClearTableFlag())) {
        support.truncateTable(config);
    }

    var r = support.safeExecuteFilePipeline(
            config.getFtpName(), filePath, fileInfo, config,
            null, detail, auditCount);

    String status = r.status() != null ? r.status() : ColumnNames.STATUS_SUCCESS;
    detailRepository.updateStatus(detailId, status, config.getNodeId());

    if (status != ColumnNames.STATUS_SUCCESS) {
        result.setOutcome(0, status, "Upload " + status);
        return;
    }
    result.setOutcome(r.records(), ColumnNames.STATUS_SUCCESS, "");
}
```

#### MultiUploadHandler.insertSingleFile

```java
private Integer insertSingleFile(String ftpName, String filePath, ResolvedPath fileInfo,
        TransferConfig config, FieldMapping mapping, Integer auditCount) {
    var r = support.safeExecuteFilePipeline(
            ftpName, filePath, fileInfo, config,
            mapping, null, auditCount);
    if (ColumnNames.STATUS_ERROR.equals(r.status())) return TASK_FAIL;
    return r.records(); // 0 for SKIPPED, >0 for SUCCESS
}
```

## 2. 标志文件操作收敛

### 2.1 `expandPathVariables` 三合一

| 当前 | 改为 |
|------|------|
| `FlagFileService.ContentEngine.expandPathVariables`（独立实现） | 委托 `TransferSupport.expandPathVariables` |
| `UploadSupport.expandPathVariables`（包装委托） | 删除，调用处直调 `TransferSupport.expandPathVariables` |

### 2.2 路径解析三合一

| 当前 | 改为 |
|------|------|
| `MultiUploadHandler.resolveFlagName`：手写 `dir + "/" + resolved` | 改用 `fileInfo.resolveRelative(resolved)` |
| `UploadSupport.resolveConfiguredFlagPath`：手写 `dir + "/" + resolved` + `normalizePathSlashes` | 改用 `fileInfo.resolveRelative(resolved)` + `FlagFileService.normalizePath` |
| `FlagFileService.resolvePath`：已用 `resolveRelative` | 不变 |

### 2.3 `normalizePath` 二合一

删除 `UploadSupport.normalizePathSlashes`，调用处改为 `FlagFileService.normalizePath`。

### 2.4 `fileNameOnly` 删除

`MultiUploadHandler.fileNameOnly` 等价于 `ResolvedPath.of(f).name()`，删除并替换。

### 2.5 `extractFlagPathPattern` 迁移

将 `UploadSupport.extractFlagPathPattern` 作为 `public static` 迁移到 `FlagFileService`，`UploadSupport` 和 `MultiUploadHandler` 统一调用 `FlagFileService.extractFlagPathPattern`。

## 3. 行为等价性验证

### 3.1 上传管线

| 场景 | truncate | preCheck | insert | postProcess | 异常处理 |
|------|----------|----------|--------|-------------|----------|
| handleSingle | 移出 FTP 回调，时序不变 ✅ | 相同 ✅ | mapping=null, detailContext=null ✅ | 相同 ✅ | `safeExecuteFilePipeline` 内建 moveToErrorDir，调用方处理 result ✅ |
| handleBatch | 移出 FTP 回调，时序不变 ✅ | 相同 ✅ | mapping=null, detailContext=detail ✅ | 相同 ✅ | 同上 + 基于 `r.status()` 更新明细状态 ✅ |
| insertSingleFile | 无（调用方统一清表）✅ | 相同 ✅ | mapping=预构建, detailContext=null ✅ | 相同 ✅ | `STATUS_ERROR` → TASK_FAIL；SKIPPED → 返回 0 ✅ |

### 3.2 标志文件操作

| 方法 | 当前行为 | 新行为 | 等价性 |
|------|---------|--------|--------|
| `resolveFlagName` | `expandPathVariables` + 手写 dir 前置 | `expandPathVariables` + `resolveRelative` | ✅ 相同算法 |
| `resolveConfiguredFlagPath` | `expandPathVariables` + 手写 dir 前置 + `normalizePathSlashes` | `expandPathVariables` + `resolveRelative` + `FlagFileService.normalizePath` | ✅ 相同算法 |
| `normalizePathSlashes` | 独立实现 | 删除，改用 `FlagFileService.normalizePath` | ✅ 相同逻辑 |
| `fileNameOnly` | 手写 | 删除，改用 `ResolvedPath.of(f).name()` | ✅ 等价 |

## 4. 不涉及的改动

- `TransferSupport` 不新增/修改方法（`expandPathVariables` 已是 public static）
- 不新增类，不改动测试
- 不改变 truncateTable 的调用时序（仅移出 FTP 回调，不改变执行顺序）
- 不改变 `MultiUploadHandler` 的并行落库逻辑