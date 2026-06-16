# FMSY 系统设计报告 v1.0

## 文档信息

| 项目 | 内容 |
|------|------|
| 项目名称 | FMSY（File Transfer Management System） |
| 版本 | V1.0 |
| 日期 | 2026-05-26 |
| 状态 | 起草 |

---

## 1. 技术选型

| 决策项 | 选择 | 说明 |
|--------|------|------|
| 技术栈 | Spring Boot 3.x + JDK 21 | 现代化Java生态 |
| 数据库访问 | JdbcTemplate简化封装 | 直接SQL控制，动态字段拼接简单 |
| FTP连接 | 连接池共享 | HikariCP风格，高效复用 |
| 并发模型 | ThreadPool + @Async | 简单直接，满足高并发需求 |
| 包结构 | 按领域组件 | polling/converter/ftphandler等 |
| 事务边界 | 文件与数据库分离 | 简单可靠，避免分布式事务 |
| 串行约束 | 内存Map检查 | UC-01轮询时加载，零额外DB开销 |

---

## 2. 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        FMSY Application                          │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐      │
│  │ Config   │   │ Polling  │   │Convert   │   │  Log     │      │
│  │ Loader   │   │&Compete  │   │ Engine   │   │ Manager  │      │
│  └──────────┘   └────┬─────┘   └────┬─────┘   └──────────┘      │
│                      │              │                              │
│                      ▼              ▼                              │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │              Converter Plugins                               │ │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐               │ │
│  │  │  DBF   │ │  XML   │ │  CSV   │ │  TXT   │               │ │
│  │  └────────┘ └────────┘ └────────┘ └────────┘               │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                      │              │                              │
│                      ▼              ▼                              │
│                 ┌─────────┐    ┌─────────┐                       │
│                 │  GaussDB│    │   FTP   │                       │
│                 └─────────┘    └─────────┘                       │
└─────────────────────────────────────────────────────────────────┘
```

### 2.1 核心流程

```
1. [启动] 初始化连接池 → 加载传输配置(UC-03) → 恢复异常中断指令(UC-22)
2. [轮询] 轮询指令表，获取待执行指令（默认获取20条）
3. [竞争] 逐条竞争指令执行权，成功则启动线程异步处理
4. [执行] 根据传输场景执行文件转换
5. [记录] 更新指令状态，写入结果表
6. [返回] 继续轮询下一批指令
```

---

## 2.5 架构设计

### 2.5.1 分层设计

FMSY应用采用**三层架构**设计，各层职责清晰，单一调用方向：

```
┌─────────────────────────────────────────────────────────────────┐
│                      Presentation Layer                          │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐                │
│  │ Polling     │ │ Convert     │ │ Lifecycle    │                │
│  │ Service     │ │ Engine      │ │ Manager     │                │
│  └─────────────┘ └─────────────┘ └─────────────┘                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Business Logic Layer                        │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ │
│  │ Upload      │ │ Download   │ │ Bucket      │ │ Audit      │ │
│  │ Handler     │ │ Handler    │ │ Service     │ │ Service    │ │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Data Access Layer                           │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ │
│  │ CommandDao  │ │ DetailDao   │ │ ResultDao   │ │ FileConverter│ │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘ │
│  ┌─────────────┐ ┌─────────────┐                                   │
│  │ FtpOperator │ │ DbOperator  │                                   │
│  └─────────────┘ └─────────────┘                                   │
└─────────────────────────────────────────────────────────────────┘
```

**各层职责**：

| 层级 | 职责 | 调用者 |
|------|------|--------|
| Presentation Layer | 轮询调度、场景分发、生命周期管理 | 框架/Spring |
| Business Logic Layer | 上传/下发处理、分桶协调、稽核校验 | Presentation Layer |
| Data Access Layer | DAO操作、文件转换、连接管理 | Business Logic Layer |

**层间调用规则**：
- **上层调用下层**：上层业务逻辑调用下层数据访问
- **下层不调用上层**：数据访问不包含业务逻辑
- **同层不相互调用**：各处理器相互独立，通过上层调度协作

---

### 2.5.2 模块设计

#### 2.5.2.1 配置模块（config）

**职责**：管理应用配置和传输规则配置

| 组件 | 职责 | 对外接口 |
|------|------|----------|
| AppConfig | 应用运行时配置（轮询间隔、线程数、超时等） | getPollingInterval(), getThreadCount() |
| ConfigLoader | 启动时加载传输配置到内存 | loadConfig(), validateConnections() |
| ConfigService | 根据类别代号+控制代号查询配置 | getConfig(category, ctrl) |
| ConfigMap | 内存配置缓存 | get(key), put(key, value) |

**关键设计**：
- 配置加载后写入内存Map，不每次查询数据库
- 连接验证失败不影响启动，只记录日志
- 配置热更新：可通过刷新接口重新加载配置

#### 2.5.2.2 轮询模块（polling）

**职责**：定时轮询指令表，竞争并调度任务

| 组件 | 职责 | 对外接口 |
|------|------|----------|
| PollingService | 定时轮询、指令去重、异常检测 | poll(), start(), stop() |
| SerialConstraint | 串行约束内存检查 | check(command) |
| TimeoutDetector | 检测并释放超时任务 | checkTimeoutTasks() |

**关键设计**：
- `@Scheduled`注解实现定时轮询
- 每次轮询加载正在处理的指令到内存Map
- 串行约束检查基于内存Map，无数据库额外开销

#### 2.5.2.3 竞争模块（competition）

**职责**：原子操作竞争指令和分桶执行权

| 组件 | 职责 | 对外接口 |
|------|------|----------|
| CompetitionService | 竞争指令执行权 | compete(commandId) |
| BucketCompetitor | 竞争分桶执行权 | competeBucket(bucketId) |

**关键设计**：
- 使用`UPDATE ... WHERE 状态='空'`原子操作
- 返回影响行数判断竞争结果
- 无悲观锁/乐观锁，纯SQL竞争

#### 2.5.2.4 转换引擎模块（converter）

**职责**：根据传输场景分发并执行上传/下发

```
ConvertEngine
    ├── SingleUploader (UPLOAD_SINGLE)
    ├── MultiUploader (UPLOAD_MULTI + 目录匹配)
    ├── BatchUploader (UPLOAD_MULTI + 明细表指定)
    ├── SingleDownloader (DOWNLOAD_SINGLE)
    ├── MultiDownloader (DOWNLOAD_SINGLE_NODE)
    └── MultiNodeDownloader (DOWNLOAD_MULTI_NODE)
```

| 组件 | 职责 | 关键方法 |
|------|------|----------|
| ConvertEngine | 场景判断，路由分发 | dispatch(command, config) |
| SingleUploader | 单文件上传处理 | process(command, config) |
| MultiUploader | 目录多文件并行上传 | process(command, config) |
| BatchUploader | 明细表指定多文件上传 | process(command, config) |
| SingleDownloader | 单文件下发处理 | process(command, config) |
| MultiDownloader | 单节点多文件下发 | processFull/processBatch |
| MultiNodeDownloader | 多节点协调下发 | process(command, config) |

#### 2.5.2.5 文件转换插件模块（converter.plugin）

**职责**：提供文件格式解析和生成能力

| 组件 | 支持格式 | 关键方法 |
|------|---------|----------|
| FileConverter | 接口定义 | parse(), generate(), countRecords() |
| DBFConverter | DBF | 读取文件头/字段区/数据区 |
| XMLConverter | XML | SAX解析、DOM生成 |
| CSVConverter | CSV | 分隔符解析 |
| TXTConverter | TXT | 分隔符/定长解析 |

**关键设计**：
- 插件式架构，新增格式只需实现接口
- 流式处理，避免大文件内存溢出
- 内嵌默认参数，配置覆盖只读部分参数

#### 2.5.2.6 FTP处理模块（ftphandler）

**职责**：FTP连接池管理和文件操作

| 组件 | 职责 | 对外接口 |
|------|------|----------|
| FtpPool | FTP连接池管理 | borrowObject(), returnObject() |
| FtpOperator | 文件操作封装 | download(), upload(), exists() |
| FtpPostProcessor | 后置文件操作 | process(config, ftp) |

**关键设计**：
- 使用Apache Commons Pool实现连接池
- 连接池大小可配置，默认20
- 连接归还机制保证复用

#### 2.5.2.7 数据库处理模块（dbhandler）

**职责**：数据库连接管理和数据操作

| 组件 | 职责 | 对外接口 |
|------|------|----------|
| JdbcTemplateHelper | JdbcTemplate封装 | update(), queryForList() |
| CommandDao | 指令表CRUD | compete(), updateStatus() |
| DetailDao | 明细表CRUD | queryByCommandId(), insert() |
| ResultDao | 结果表CRUD | write(), queryByCommandId() |
| DbWriter | 数据写入 | write(data, config) |
| DbReader | 数据读取 | read(config), readBatch(config) |
| DbMetadata | 元数据查询（支持分区表探测） | getTableMetadata(), getPartitions() |

**关键设计**：
- DbMetadata组件负责查询表元数据，判断是否为分区表
- 下发时自动探测分区表，按分区遍历下发
- 分区信息缓存，避免重复查询

**DbMetadata接口**：
```java
public interface DbMetadata {
    // 获取表元数据
    Map<String, Object> getTableMetadata(String tableName);

    // 获取分区列表（分区表）
    List<String> getPartitions(String tableName);

    // 获取分区字段
    List<String> getPartitionColumns(String tableName);

    // 判断是否为分区表
    boolean isPartitioned(String tableName);
}
```

**GaussDB分区查询SQL**：
```sql
-- 查询表是否为分区表
SELECT PARTITIONED FROM USER_TABLES WHERE TABLE_NAME = ?

-- 查询分区列表
SELECT PARTITION_NAME FROM USER_TAB_PARTITIONS WHERE TABLE_NAME = ?

-- 查询分区字段
SELECT PARTITION_EXPRESSION FROM USER_PART_KEY_COLUMNS WHERE TABLE_NAME = ?
```

#### 2.5.2.8 稽核模块（audit）

**职责**：执行前后校验数据一致性

| 组件 | 职责 | 对外接口 |
|------|------|----------|
| PreAuditor | 前稽核 | audit(command, config) |
| PostAuditor | 后稽核 | auditAfterUpload(), auditAfterDownload() |

**关键设计**：
- 上传前：比对文件记录数与稽核数
- 上传后：比对文件记录数与数据库表记录数
- 下发前：比对数据库记录数与稽核数
- 下发后：比对生成文件记录数与数据库记录数

#### 2.5.2.9 占位符解析模块（placeholder）

**职责**：解析文件路径中的占位符

| 组件 | 职责 | 对外接口 |
|------|------|----------|
| PlaceholderResolver | 占位符解析替换 | resolve(template, context) |

**支持占位符类型**：
- 日期：`$YYYYMMDD$`、`$YYYYMMDDHHmmss$`
- 指令字段：`$EXTRA_INFO$`
- 替换字段：`$FIELD_NAME$`

#### 2.5.2.10 错误处理模块（error）

**职责**：处理处理失败的异常

| 组件 | 职责 | 对外接口 |
|------|------|----------|
| ErrorFileHandler | 错误文件移动到error目录 | handle(command, config, ftp, filePath) |
| ErrorResultRecorder | 错误结果写入结果表 | recordError(command, errorType) |

**关键设计**：
- 错误文件移动到源文件同层级的error目录
- 错误文件名格式：`原文件名_时间戳.扩展名`
- 标志文件同步移动到error目录

#### 2.5.2.11 日志模块（logging）

**职责**：结构化日志输出

| 组件 | 职责 | 对外接口 |
|------|------|----------|
| LogManager | 日志写入 | info(), warn(), error() |

**日志格式**：
```
yyyy-MM-dd HH:mm:ss.SSS LEVEL [TASK-id] [NODE-name] 阶段 | 描述 | 详情
```

---

### 2.5.3 错误处理设计

#### 2.5.3.1 错误分类

| 错误类型 | 场景 | 处理策略 | 结果状态 |
|----------|------|----------|----------|
| 前置检查失败 | 标志文件不存在 | 跳过当前文件 | N |
| 前置校验失败 | 标志文件内容不匹配 | 异常结束 | E |
| 前稽核失败 | 记录数不匹配 | 回滚，错误文件处理 | E |
| 后稽核失败 | 记录数不匹配 | 回滚，错误文件处理 | E |
| 数据库异常 | 连接失败/SQL错误 | 异常结束 | E |
| FTP异常 | 连接失败/文件不存在 | 异常结束 | E |
| 转换异常 | 解析错误/生成错误 | 回滚，错误文件处理 | E |
| 覆盖检查失败 | 文件已存在且不允许覆盖 | 跳过当前文件 | E |
| 超时释放 | 任务执行超1小时 | 自动释放 | E |

#### 2.5.3.2 错误处理策略

**错误处理原则**：
1. **分层处理**：各层只处理本层异常，上层捕获下层异常
2. **快速失败**：检测到异常立即失败，不继续处理
3. **资源释放**：无论成功失败，必须释放连接等资源
4. **结果记录**：异常信息写入结果表，便于问题排查

**错误处理流程**：
```
Business Layer
    │
    ├─ 捕获异常
    │
    ├─ 判断错误类型
    │   ├─ 业务异常（如前置检查失败）→ 返回Result(N)
    │   └─ 系统异常（如数据库错误）→ 返回Result(E)
    │
    ├─ 资源释放
    │   ├─ 事务回滚（如已开启）
    │   └─ 关闭FTP连接
    │
    ├─ 错误文件处理（如需要）
    │   └─ 移动到error目录
    │
    └─ 写入结果表
        └─ 结果说明字段记录异常信息
```

#### 2.5.3.3 异常类设计

```java
// 业务异常（不严重，可跳过）
public class BusinessException extends RuntimeException {
    private String resultStatus; // N 或 E
    public BusinessException(String message, String resultStatus) { ... }
}

// 系统异常（严重，需记录堆栈）
public class SystemException extends RuntimeException {
    private String resultStatus = "E";
    public SystemException(String message, Throwable cause) { ... }
}

// 稽核异常
public class AuditException extends SystemException {
    private int expected;
    private int actual;
    public AuditException(int expected, int actual) { ... }
}

// 配置异常（启动失败）
public class ConfigException extends RuntimeException {
    public ConfigException(String message) { ... }
}
```

#### 2.5.3.4 资源释放设计

**使用try-with-resources确保资源释放**：
```java
// FTP连接释放
try (FtpClient ftp = ftpPool.borrowObject()) {
    // 使用ftp
} // 自动归还到连接池

// 数据库连接由连接池管理，无需显式关闭
```

**事务回滚**：
```java
@Transactional(rollbackFor = Exception.class)
public UploadResult process(Map<String, Object> command, Map<String, Object> config) {
    try {
        // 1. 写入数据库
        dbWriter.write(data, config);
        
        // 2. 后稽核
        if (!postAuditor.auditAfterUpload(file, config)) {
            throw new AuditException(expected, actual);
        }
        
        return UploadResult.success();
    } catch (Exception e) {
        // 事务自动回滚
        throw e;
    }
}
```

#### 2.5.3.5 错误日志设计

**错误日志必须包含**：
- 时间戳
- 任务ID
- 节点ID
- 错误阶段
- 错误类型
- 错误信息
- 异常堆栈（系统异常）

**日志格式示例**：
```
2026-05-22 10:30:15.123 ERROR [TASK-12345] [NODE-A] 后稽核 | 文件=test.csv | 预期=1000 | 实际=999
2026-05-22 10:30:15.456 ERROR [TASK-12345] [NODE-A] 数据库写入 | 连接超时
java.sql.SQLException: Connection timeout
    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:...)
    at com.fmsy.dbhandler.JdbcWriter.write(JdbcWriter.java:...)
```

---

## 3. 包结构设计

```
com.fmsy
├── FmsyApplication               # 启动入口
│
├── config                         # 配置相关
│   ├── AppConfig                  # 应用配置（轮询间隔、线程数等）
│   ├── ConfigLoader               # 传输配置加载（UC-03）
│   └── ConfigService             # 配置查询服务（UC-04）
│
├── polling                        # 指令轮询
│   └── PollingService             # UC-01：轮询指令表、竞争调度
│
├── competition                    # 竞争执行权
│   └── CompetitionService         # UC-02：原子UPDATE竞争
│
├── converter                      # 转换引擎
│   ├── ConvertEngine             # 转换调度
│   ├── plugin                     # 插件接口
│   │   └── FileConverter         # 转换器接口
│   ├── upload                     # 上传处理器
│   │   ├── SingleUploader         # UC-05.1：单文件上传
│   │   ├── MultiUploader         # UC-05.2：目录多文件上传
│   │   └── BatchUploader         # UC-05.3：指定多文件上传
│   └── download                   # 下发处理器
│       ├── SingleDownloader      # UC-06.1：单文件下发
│       ├── MultiDownloader       # UC-06.2/06.3：单节点多文件下发
│       └── MultiNodeDownloader   # UC-06.4：多节点协调下发
│
├── ftphandler                     # FTP操作
│   ├── FtpPool                   # FTP连接池
│   ├── FtpOperator               # FTP操作
│   └── FtpPostProcessor           # UC-09：后置文件操作（RENAME/DELETE）
│
├── dbhandler                      # 数据库操作
│   ├── JdbcTemplateHelper        # JdbcTemplate封装
│   ├── CommandDao               # 指令表操作
│   ├── DetailDao                # 明细表操作
│   ├── ResultDao                # 结果表操作
│   └── ConfigDao                # 配置表操作
│
├── audit                          # 稽核
│   ├── PreAuditor               # UC-11：执行前稽核
│   └── PostAuditor              # UC-12：执行后稽核
│
├── preprocessor                   # 前置处理
│   └── PreFileChecker            # UC-08：标志文件检查
│
├── placeholder                    # 占位符解析
│   └── PlaceholderResolver      # UC-10：解析占位符
│
├── state                          # 状态管理
│   ├── SerialConstraint         # UC-17：串行约束检查（内存Map）
│   └── CommandStatus            # 状态枚举
│
├── bucket                         # 分桶处理
│   ├── BucketService            # UC-07：轮询分桶明细
│   └── BucketProcessor          # 分桶执行
│
├── result                         # 结果写入
│   └── ResultWriter              # UC-19：写入结果表
│
├── error                          # 错误处理
│   └── ErrorFileHandler          # UC-14：错误文件处理
│
├── directory                     # 目录生成
│   └── DirectoryCreator        # UC-18：生成下发目录
│
├── postprocess                   # 后置处理
│   └── PostFileProcessor        # UC-09：后置文件操作
│
└── logging                        # 日志
    └── LogManager                # UC-15：写日志
```

---

## 4. 用例详细设计

### 4.1 UC-01：轮询指令表

**类**：PollingService

**职责**：定时轮询指令表，获取待执行指令，超时检测，串行约束检查前的前置加载

**核心逻辑**：
```java
@Scheduled(fixedDelayString = "${app.polling.interval:10000}")
public void poll() {
    try {
        // 1. 检查超时任务（执行超过1小时）
        checkTimeoutTasks();

        // 2. 查询正在处理的指令，加载到内存Map（供串行约束检查使用）
        loadProcessingCommands();

        // 3. 查询就绪指令
        List<Map<String, Object>> commands = queryReadyCommands();

        // 4. 遍历指令，逐条竞争
        for (Map<String, Object> cmd : commands) {
            // 5. 串行约束检查（UC-17）
            if (!serialConstraint.check(cmd)) {
                log.debug("指令跳过，串行约束检查未通过: {}", cmd.get("自增列"));
                continue;
            }

            // 6. 竞争执行权（UC-02）
            boolean success = competitionService.compete(cmd);
            if (success) {
                // 7. 异步处理
                asyncProcess(cmd);
            }
        }
    } catch (Exception e) {
        log.error("轮询异常", e);
    }
}
```

**超时检测**：
```sql
UPDATE 指令表
SET 处理状态='E', 处理结束时间=NOW()
WHERE 处理状态='P'
  AND 处理节点='当前节点ID'
  AND 处理起始时间 < NOW() - INTERVAL 1 HOUR
```

**超时处理时写入结果表**：
```java
private void checkTimeoutTasks() {
    String sql = """
        UPDATE 指令表
        SET 处理状态='E', 处理结束时间=NOW()
        WHERE 处理状态='P'
          AND 处理节点=?
          AND 处理起始时间 < NOW() - INTERVAL 1 HOUR
        """;
    int[] updated = jdbc.update(sql, nodeId);
    for (int i = 0; i < updated.length; i++) {
        // 插入结果表：处理结果='E'，结果说明='执行超时自动释放'
    }
}
```

**加载正在处理的指令到内存**：
```sql
SELECT 处理节点, 类别代号, 控制代号, 指令类型
FROM 指令表
WHERE 处理状态='P' AND 处理节点 IS NOT NULL
```

**获取就绪指令**：
```sql
SELECT 自增列, 类别代号, 控制代号, 指令类型, 稽核数, 额外信息
FROM 指令表
WHERE 处理状态='空' AND 处理节点 IS NULL
ORDER BY 自增列 ASC
LIMIT 20
```

**优化说明**：
- 相同类别代号+控制代号的就绪指令只取最早那条（去重）
- 多节点同时轮询时，各自获取的列表自然不同

---

### 4.2 UC-02：竞争指令执行权

**类**：CompetitionService

**职责**：原子UPDATE竞争指令执行权

```java
public boolean compete(Long commandId) {
    String sql = """
        UPDATE 指令表
        SET 处理节点=?, 处理起始时间=NOW(), 处理状态='P'
        WHERE 自增列=? AND 处理状态='空' AND 处理节点 IS NULL
        """;
    int rows = jdbc.update(sql, nodeId, commandId);
    return rows == 1;
}
```

**返回**：
- true（影响行数=1）：竞争成功
- false（影响行数=0）：已被其他节点抢走

**S类型子指令竞争**（与普通指令相同SQL）：
```java
public boolean competeSType(Long commandId) {
    String sql = """
        UPDATE 指令表
        SET 处理节点=?, 处理起始时间=NOW(), 处理状态='P'
        WHERE 自增列=? AND 处理状态='空' AND 处理节点 IS NULL
        """;
    int rows = jdbc.update(sql, nodeId, commandId);
    return rows == 1;
}
```

---

### 4.3 UC-03：加载传输配置

**类**：ConfigLoader

**职责**：系统启动时加载所有传输配置到内存，验证连接

```java
public void loadConfig() {
    // 1. 查询所有有效配置
    List<Map<String, Object>> configs = jdbc.queryForList(
        "SELECT * FROM 传输配置表 WHERE 状态='有效'"
    );

    // 2. 写入内存Map（key = 类别代号 + 控制代号）
    for (Map<String, Object> config : configs) {
        String key = makeKey(config);
        configMap.put(key, config);
    }

    // 3. 初始化转换器插件
    initConverters();

    // 4. 验证数据库连接
    validateDbConnections();

    // 5. 验证FTP连接
    validateFtpConnections();

    log.info("配置加载完成，共加载 {} 条规则", configs.size());
}
```

**内存结构**：
```java
private final Map<String, Map<String, Object>> configMap = new ConcurrentHashMap<>();

private String makeKey(Map<String, Object> config) {
    return config.get("类别代号") + "|" + config.get("控制代号");
}
```

**连接验证**：
```java
private void validateDbConnections() {
    Set<String> dbNames = extractUniqueDbNames(configMap.values());
    for (String dbName : dbNames) {
        try {
            DbConnection conn = dbConnectionFactory.getConnection(dbName);
            conn.execute("SELECT 1");
            conn.close();
        } catch (Exception e) {
            log.error("数据库连接验证失败: {}", dbName, e);
        }
    }
}
```

---

### 4.4 UC-04：查询传输配置

**类**：ConfigService

**职责**：根据类别代号和控制代号从内存查询传输配置

```java
public Map<String, Object> getConfig(String category, String ctrl) {
    String key = category + "|" + ctrl;
    Map<String, Object> config = configMap.get(key);
    if (config == null) {
        throw new ConfigNotFoundException(category, ctrl);
    }
    return config;
}
```

**配置解析**：返回配置Map，包含以下字段：

| 字段 | 说明 |
|------|------|
| 传输场景 | UPLOAD_SINGLE/UPLOAD_MULTI/DOWNLOAD_SINGLE等 |
| 文件路径 | 单文件路径或目录路径（支持占位符） |
| 数据库表名 | 目标表或源表 |
| 清表标识 | Y/N |
| 覆盖标识 | Y/N |
| 并发数 | 数值 |
| 串行标识 | Y/N |
| 解析器类型 | CSVConverter/DBFConverter等 |
| 解析配置 | 各格式解析时的额外自定义参数（JSON格式） |
| 前置文件操作 | 操作类型;参数,... |
| 后置文件操作 | 操作类型;参数,... |
| 忽略字段配置 | 不上传/不下发的字段列表 |
| 拆分字段配置 | 下发时的分桶字段 |
| FTP名称 | FTP配置文件中的连接名称 |
| 数据库名称 | 数据库配置文件中的连接名称 |
| 空数据处理 | ERROR/SEND_EMPTY/SKIP（下发）/ERROR/ALLOW（上传） |

---

### 4.5 UC-05：执行上传任务

#### 4.5.1 UC-05.1 单文件上传

**类**：SingleUploader

**适用条件**：传输场景=UPLOAD_SINGLE

**流程**：
```java
public UploadResult process(Map<String, Object> command, Map<String, Object> config) {
    long startTime = System.currentTimeMillis();

    // 1. 解析占位符（UC-10）
    String filePath = placeholderResolver.resolve(config.get("文件路径"), command);

    // 2. 前置处理（UC-08）
    PreCheckResult preCheck = preChecker.check(filePath, config.get("前置文件操作"));
    if (!preCheck.isPass()) {
        return UploadResult.skip(preCheck.getReason());
    }

    // 3. 连接FTP
    try (FtpClient ftp = ftpPool.borrowObject()) {
        // 4. 前稽核（UC-11）
        if (!preAuditor.audit(command, config, ftp, filePath)) {
            return UploadResult.error("前稽核失败");
        }

        // 5. 下载文件到本地临时文件
        File tempFile = ftp.downloadToTemp(filePath);

        // 6. 解析文件
        FileConverter converter = getConverter(config);
        Iterator<List<Map<String, Object>>> data = converter.parse(
            new FileInputStream(tempFile), parseConfig(config)
        );

        // 7. 写入数据库
        int recordCount = dbWriter.write(data, config);

        // 8. 后稽核（UC-12）
        if (!postAuditor.auditAfterUpload(tempFile, config)) {
            dbWriter.rollback();
            errorFileHandler.handle(command, config, ftp, filePath);
            return UploadResult.error("后稽核失败");
        }

        // 9. 后置处理（UC-09）
        postProcessor.process(config, ftp);

        // 10. 删除临时文件
        tempFile.delete();

        return UploadResult.success(recordCount, System.currentTimeMillis() - startTime);
    }
}
```

**空数据处理**：
- 空数据 + ERROR → 返回结果'E'，不写入数据库
- 空数据 + ALLOW → 返回结果'Y'，不写入数据库
- 结果'N'代表文件未就绪（标志文件未到达），与空数据处理无关

---

#### 4.5.2 UC-05.2 目录多文件上传

**类**：MultiUploader

**适用条件**：传输场景=UPLOAD_MULTI，指令类型='空'，占位符解析后匹配到多个文件

**流程**：
```java
public UploadResult process(Map<String, Object> command, Map<String, Object> config) {
    // 1. 解析占位符
    String dirPath = placeholderResolver.resolve(config.get("文件路径"), command);

    // 2. 连接FTP，列出匹配的文件
    List<String> files = ftp.listFiles(dirPath, "*.csv");

    // 3. 主线程执行前置处理
    PreCheckResult preCheck = preChecker.check(dirPath, config.get("前置文件操作"));
    if (!preCheck.isPass()) {
        return UploadResult.skip(preCheck.getReason());
    }

    // 4. 启动多线程并行处理
    int threadCount = Math.min(files.size(), config.getInt("并发数", 3));
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    List<Future<UploadResult>> futures = new ArrayList<>();

    for (String file : files) {
        futures.add(executor.submit(() -> processFile(command, config, file)));
    }

    // 5. 汇总结果
    int totalRecords = 0;
    boolean hasError = false;
    boolean hasSkip = false;

    for (Future<UploadResult> f : futures) {
        UploadResult r = f.get();
        totalRecords += r.getRecordCount();
        if (r.isError()) hasError = true;
        if (r.isSkip()) hasSkip = true;
    }

    // 6. 决定最终状态
    String finalStatus;
    if (hasError) finalStatus = "E";
    else if (hasSkip && !hasError) finalStatus = "Y"; // 部分成功仍算成功
    else finalStatus = "Y";

    return new UploadResult(finalStatus, totalRecords);
}
```

**并行控制**：
- 线程数 = MIN(匹配文件数, 配置并发数)
- 每文件独立事务，互不影响

---

#### 4.5.3 UC-05.3 指定多文件上传

**类**：BatchUploader

**适用条件**：传输场景=UPLOAD_MULTI，指令类型='R'

**流程**：
```java
public UploadResult process(Map<String, Object> command, Map<String, Object> config) {
    Long commandId = (Long) command.get("自增列");

    // 1. 根据指令ID查询明细表
    List<Map<String, Object>> details = detailDao.queryByCommandId(commandId);

    // 2. 遍历明细记录
    for (Map<String, Object> detail : details) {
        // 3. 根据明细表的指定字段取值填充占位符生成文件路径
        String filePath = placeholderResolver.resolveWithDetail(
            config.get("文件路径"), command, detail
        );

        // 4. 处理单个文件
        UploadResult r = processFile(command, config, filePath, detail);

        // 5. 更新明细表状态
        detailDao.updateStatus(detail.getId(), r.getStatus());
    }

    // 6. 汇总结果并返回
    return aggregateResults(details);
}
```

**明细表字段使用**：

| 字段 | 用途 |
|------|------|
| 指定文件名 | 填充占位符用于定位FTP上的文件 |
| 指定字段名称 | 额外落库字段名称（多字段逗号分割） |
| 指定字段取值 | 对应字段的值（与指定字段名称顺序对应） |
| 稽核数 | 该文件的记录数，用于前稽核 |

**字段映射（额外字段处理）**：
```java
// 如果明细表指定了额外字段
String extraColumns = detail.get("指定字段名称");  // 如 "REGION,STATUS"
String extraValues = detail.get("指定字段取值");  // 如 "EAST,ACTIVE"

// 动态INSERT
String sql = "INSERT INTO " + tableName + " (" + normalColumns + "," + extraColumns + ") " +
              "VALUES (" + normalValues + "," + extraValues + ")";
```

---

### 4.6 UC-06：执行下发任务

#### 4.6.1 UC-06.1 单文件下发

**类**：SingleDownloader

**适用条件**：传输场景=DOWNLOAD_SINGLE

**流程**：
```java
public DownloadResult process(Map<String, Object> command, Map<String, Object> config) {
    // 1. 解析占位符
    String filePath = placeholderResolver.resolve(config.get("文件路径"), command);

    // 2. 检查覆盖标识
    if ("N".equals(config.get("覆盖标识")) && ftp.exists(filePath)) {
        return DownloadResult.error("文件已存在，不允许覆盖");
    }

    // 3. 连接FTP，创建目录（如路径中包含目录）
    createDirectoryIfNeeded(filePath);

    // 4. 前置处理
    PreCheckResult preCheck = preChecker.check(filePath, config.get("前置文件操作"));
    if (!preCheck.isPass()) {
        return DownloadResult.skip(preCheck.getReason());
    }

    // 5. 前稽核（UC-11）
    if (!preAuditor.audit(command, config)) {
        return DownloadResult.error("前稽核失败");
    }

    // 6. 从数据库读取数据
    List<Map<String, Object>> data = dbReader.read(config);

    // 7. 空数据处理
    if (data.isEmpty()) {
        String emptyHandle = config.get("空数据处理");
        if ("ERROR".equals(emptyHandle)) {
            return DownloadResult.error("空数据不允许下发");
        } else if ("SKIP".equals(emptyHandle)) {
            return DownloadResult.skip("空数据跳过");
        } else { // SEND_EMPTY
            // 继续生成空文件
        }
    }

    // 8. 生成文件
    FileConverter converter = getConverter(config);
    OutputStream out = ftp.uploadStream(filePath);
    converter.generate(out, data.iterator(), parseConfig(config));
    out.close();

    // 9. 后置处理
    postProcessor.process(config, ftp);

    // 10. 后稽核（UC-12）
    if (!postAuditor.auditAfterDownload(filePath, config)) {
        ftp.delete(filePath); // 删除生成的文件
        return DownloadResult.error("后稽核失败");
    }

    return DownloadResult.success(data.size());
}
```

---

#### 4.6.2 UC-06.2 单节点多文件下发（全表）

**类**：MultiDownloader.processFull()

**适用条件**：传输场景=DOWNLOAD_SINGLE_NODE，指令类型='空'，配置拆分字段

**说明**：单节点处理，不创建S类型子指令，不写明细表，直接遍历分桶生成文件

**流程**：
```java
public DownloadResult processFull(Map<String, Object> command, Map<String, Object> config) {
    // 1. 查询数据库表，按拆分字段分组获取所有分桶取值
    String splitFields = config.get("拆分字段配置"); // 如 "REGION,STATUS"
    List<Map<String, String>> buckets = dbReader.groupBySplitFields(splitFields, config);

    // 2. 创建下发目录
    String dirPath = extractDirectoryPath(config.get("文件路径"));
    ftp.createDirectory(dirPath);

    // 3. 前置处理
    PreCheckResult preCheck = preChecker.check(dirPath, config.get("前置文件操作"));
    if (!preCheck.isPass()) {
        return DownloadResult.skip(preCheck.getReason());
    }

    // 4. 遍历每个分桶
    for (Map<String, String> bucket : buckets) {
        processBucket(command, config, bucket);
    }

    // 5. 生成总标志文件
    postProcessor.generateTotalFlag(config, ftp, dirPath);

    return aggregateResults();
}
```

**分桶处理**：
```java
private void processBucket(Map<String, Object> command, Map<String, Object> config,
                           Map<String, String> bucketValues) {
    // 1. 根据分桶的拆分字段取值填充占位符
    String filePath = placeholderResolver.resolveWithBucket(
        config.get("文件路径"), bucketValues
    );

    // 2. 前稽核（UC-11）
    int auditCount = detailDao.getAuditCount(bucketValues);
    if (!preAuditor.audit(command, config, auditCount)) {
        return; // 跳过该分桶
    }

    // 3. 从数据库读取数据（按分桶字段取值筛选）
    List<Map<String, Object>> data = dbReader.readWithFilter(config, bucketValues);

    // 4. 空数据处理
    if (data.isEmpty()) {
        String emptyHandle = config.get("空数据处理");
        if ("SKIP".equals(emptyHandle)) {
            detailDao.updateStatus(bucketValues, "N");
            return;
        }
    }

    // 5. 生成文件
    FileConverter converter = getConverter(config);
    converter.generate(ftp.uploadStream(filePath), data.iterator(), config);

    // 6. 生成子文件标志
    postProcessor.generateSubFlag(config, ftp, filePath);

    // 7. 更新明细表状态
    detailDao.updateStatus(bucketValues, "Y");
}
```

---

#### 4.6.2.5 分区表处理（扩展）

**适用条件**：下发时源数据库表为分区表，需要按分区遍历下发

**分区判断流程**：
```java
public List<Map<String, Object>> readWithPartition(Map<String, Object> config,
                                                   Map<String, String> bucketValues) {
    String tableName = config.get("数据库表名");

    // 1. 查询表元数据，判断是否为分区表
    Map<String, Object> tableMeta = dbMetadata.getTableMetadata(tableName);
    boolean isPartitioned = "PARTITIONED".equals(tableMeta.get("partitionType"));

    if (!isPartitioned) {
        // 非分区表：普通查询
        return readWithFilter(config, bucketValues);
    }

    // 2. 获取分区字段和分区列表
    List<String> partitions = dbMetadata.getPartitions(tableName);

    // 3. 按分区遍历读取
    List<Map<String, Object>> allData = new ArrayList<>();
    for (String partition : partitions) {
        List<Map<String, Object>> partitionData =
            readPartitionData(config, bucketValues, partition);
        allData.addAll(partitionData);
    }
    return allData;
}
```

**分區讀取SQL**：
```sql
-- 查询分区表的所有分区
SELECT PARTITION_NAME FROM USER_TAB_PARTITIONS WHERE TABLE_NAME = ?

-- 按分区查询数据
SELECT * FROM {表名} PARTITION ({分区名}) WHERE {拆分字段}=?
```

**分区下发文件名示例**：
- 源表按REGION分区：EAST、WEST、NORTH
- 文件路径：`/data/export/$YYYYMMDD$/$REGION$.dbf`
- 生成文件：`/data/export/20260525/EAST.dbf`、`/data/export/20260525/WEST.dbf`、`/data/export/20260525/NORTH.dbf`

**配置增强**：
| 配置项 | 说明 | 示例 |
|--------|------|------|
| 分区模式 | AUTO（自动探测分区）/MANUAL（手动指定） | AUTO |
| 分区字段列表 | 手动指定分区时的分区名 | EAST,WEST,NORTH |
| 分区过滤条件 | 分区内WHERE条件 | STATUS='ACTIVE' |

**分区模式下发配置示例**：
```json
{
  "配置ID": 5,
  "类别代号": "TEST05",
  "控制代号": "FILE005",
  "传输场景": "DOWNLOAD_SINGLE_NODE",
  "文件路径": "/data/export/$YYYYMMDD$/$REGION$.dbf",
  "数据库表名": "BIG_TABLE",
  "拆分字段配置": "REGION",
  "分区模式": "AUTO",
  "解析器类型": "DBFConverter",
  "数据库名称": "DB_PRIMARY"
}
```

---

#### 4.6.3 UC-06.3 单节点多文件下发（指定）

**类**：MultiDownloader.processBatch()

**适用条件**：传输场景=DOWNLOAD_SINGLE_NODE，指令类型='R'

**流程**：
```java
public DownloadResult processBatch(Map<String, Object> command, Map<String, Object> config) {
    Long commandId = (Long) command.get("自增列");

    // 1. 根据指令ID查询明细表
    List<Map<String, Object>> details = detailDao.queryByCommandId(commandId);

    // 2. 创建下发目录
    String dirPath = extractDirectoryPath(config.get("文件路径"));
    ftp.createDirectory(dirPath);

    // 3. 遍历明细记录
    for (Map<String, Object> detail : details) {
        // 4. 根据明细表的分桶取值填充占位符
        String filePath = placeholderResolver.resolveWithDetail(
            config.get("文件路径"), detail
        );

        // 5. 处理分桶
        processBucket(command, config, detail);

        // 6. 更新明细表状态
        detailDao.updateStatus(detail.getId(), result.getStatus());
    }

    // 7. 生成总标志文件
    postProcessor.generateTotalFlag(config, ftp, dirPath);

    return aggregateResults(details);
}
```

---

#### 4.6.4 UC-06.4 多节点协调拆分下传

**类**：MultiNodeDownloader

**适用条件**：传输场景=DOWNLOAD_MULTI_NODE

**主节点初始化流程**：
```java
public DownloadResult process(Map<String, Object> command, Map<String, Object> config) {
    String cmdType = (String) command.get("指令类型");

    if ("空".equals(cmdType)) {
        // 1. 查询数据库，按拆分字段分组获取所有分桶
        String splitFields = config.get("拆分字段配置");
        List<Map<String, String>> buckets = dbReader.groupBySplitFields(splitFields, config);

        // 2. 写入明细表（状态='空'）
        for (Map<String, String> bucket : buckets) {
            detailDao.insert(command.getId(), bucket);
        }
    }
    // 如果指令类型='R'，明细表已有分桶数据，跳过此步骤

    // 3. 插入M条S类型子指令
    int subCommandCount = calculateSubCommandCount(command);
    for (int i = 0; i < subCommandCount; i++) {
        commandDao.insertSTypeCommand(command, subCommandId);
    }

    // 4. 竞争并处理S类型子指令（UC-07）
    bucketService.process(command.getId());

    // 5. 主节点监控完成并生成总标志文件
    return monitorAndFinalize(command);
}
```

**S类型子指令创建SQL**：
```sql
INSERT INTO 指令表 (类别代号, 控制代号, 指令类型, 额外信息, 稽核数)
VALUES (?, ?, 'S', ?, -1)
```

---

### 4.7 UC-07：轮询分桶明细

**类**：BucketService

**职责**：各节点竞争轮询明细表中的就绪分桶

```java
public void process(Long mainCommandId) {
    while (true) {
        // 1. 竞争一个S类型子指令
        Map<String, Object> stc = competitionService.competeSType(mainCommandId);
        if (stc == null) {
            break; // 无可竞争的S类型子指令
        }

        // 2. 循环处理该主指令对应的就绪分桶
        processBuckets(mainCommandId);

        // 3. S类型子指令完成，更新状态为'Y'或'E'
        updateSTypeCommandStatus(stc);

        // 4. 写入该S类型子指令的结果记录
        resultWriter.write(stc, stc.getStatus());
    }
}

private void processBuckets(Long mainCommandId) {
    while (true) {
        // 1. 查询一批就绪分桶
        List<Map<String, Object>> buckets = detailDao.queryReadyBuckets(mainCommandId, batchSize);
        if (buckets.isEmpty()) {
            break;
        }

        // 2. 遍历这批分桶，逐条竞争处理
        for (Map<String, Object> bucket : buckets) {
            boolean success = competitionService.competeBucket(bucket);
            if (!success) continue;

            // 3. 竞争成功，处理该分桶
            processBucket(bucket);
        }
    }
}
```

**竞争分桶SQL**：
```sql
UPDATE 明细表
SET 处理节点=?, 处理状态='P'
WHERE 自增列=? AND 处理状态='空'
```

---

### 4.8 UC-08：校验标志文件

**类**：PreFileChecker

**职责**：上传前检测并校验标志文件

```java
public PreCheckResult check(String filePath, String preOperations) {
    if (preOperations == null || preOperations.isEmpty()) {
        return PreCheckResult.pass();
    }

    String[] operations = preOperations.split(",");

    for (String op : operations) {
        String[] parts = op.split(";");
        String type = parts[0];

        switch (type) {
            case "CHECK_READY" -> {
                // 检查文件是否存在
                String pattern = parts[1];
                if (!ftp.exists(filePath, pattern)) {
                    return PreCheckResult.skip("文件不存在: " + pattern);
                }
            }
            case "CHECK_FLAG" -> {
                // 检查标志文件
                String flagPattern = parts[1];
                String nullPattern = parts.length > 2 ? parts[2] : null;

                if (!ftp.exists(filePath, flagPattern)) {
                    return PreCheckResult.skip("标志文件不存在: " + flagPattern);
                }

                String flagContent = ftp.read(flagPattern);
                if (isNullPattern(flagContent, nullPattern)) {
                    // 空模式，跳过校验
                    continue;
                }

                // 用数据文件内容校验
                int expected = parseExpected(flagContent);
                int actual = countFileRecords(filePath);
                if (expected != actual) {
                    return PreCheckResult.error("标志文件校验失败: 期望" + expected + ", 实际" + actual);
                }
            }
        }
    }
    return PreCheckResult.pass();
}
```

**空模式判断**：
```java
private boolean isNullPattern(String content, String nullPattern) {
    if (nullPattern == null) return false;
    return content == null || content.isEmpty() ||
           nullPattern.contains(content) ||
           "NULL".equalsIgnoreCase(content) ||
           "EMPTY".equalsIgnoreCase(content) ||
           "0".equals(content);
}
```

---

### 4.9 UC-09：处理标志文件

**类**：PostFileProcessor

**职责**：上传完成后处理标志文件（重命名/删除/生成反馈文件）

**操作类型**：

| 操作类型 | 参数 | 说明 |
|----------|------|------|
| RENAME | 原文件模式;目标文件模式 | 重命名文件 |
| DELETE | 文件名模式 | 删除匹配的文件 |
| GENERATE_FEEDBACK | 文件名模式;内容取值 | 生成反馈文件 |

```java
public void process(String filePath, String postOperations, FtpClient ftp) {
    if (postOperations == null || postOperations.isEmpty()) {
        return;
    }

    String[] operations = postOperations.split(",");

    for (String op : operations) {
        String[] parts = op.split(";");
        String type = parts[0];

        switch (type) {
            case "RENAME" -> {
                String pattern = parts[1];
                String targetPattern = parts[2];
                String actualFile = ftp.findFile(filePath, pattern);
                String targetFile = resolveFileName(actualFile, targetPattern);
                ftp.rename(actualFile, targetFile);
            }
            case "DELETE" -> {
                String pattern = parts[1];
                ftp.delete(filePath, pattern);
            }
            case "GENERATE_FEEDBACK" -> {
                String pattern = parts[1];
                String content = parts[2];
                String feedbackFile = resolveFileName(filePath, pattern);
                ftp.write(feedbackFile, content);
            }
        }
    }
}
```

**文件名模式变量**：
- `{subfile}` - 当前子文件名（不含扩展名）
- `{dirname}` - 当前目录名

---

### 4.10 UC-10：解析占位符

**类**：PlaceholderResolver

**职责**：解析文件名中的占位符并替换

**占位符类型**：

| 类型 | 示例 | 取值来源 |
|------|------|----------|
| 日期 | `$YYYYMMDD$` | 系统当前日期 |
| 时间 | `$YYYYMMDDHHmmss$` | 系统当前时间 |
| 指令字段 | `$EXTRA_INFO$` | 指令表额外信息字段 |
| 替换字段 | `$REGION$`、`$STATUS$` | 按拆分字段配置从数据源取值 |

```java
public String resolve(String template, Map<String, Object> context) {
    String result = template;

    // 1. 日期时间占位符
    result = result.replace("$YYYYMMDD$", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
    result = result.replace("$YYYYMMDDHHmmss$", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));

    // 2. 指令字段占位符
    result = resolvePlaceholders(result, context, "指令");

    // 3. 替换字段占位符
    result = resolvePlaceholders(result, context, "替换字段");

    return result;
}

public String resolveWithDetail(String template, Map<String, Object> command,
                                 Map<String, Object> detail) {
    Map<String, Object> context = new HashMap<>(command);
    context.putAll(detail);
    return resolve(template, context);
}

public String resolveWithBucket(String template, Map<String, String> bucketValues) {
    Map<String, Object> context = new HashMap<>(bucketValues);
    return resolve(template, context);
}
```

**示例**：
- 文件路径：`/data/upload/student_$YYYYMMDD$.csv` + 日期=20260525 → `/data/upload/student_20260525.csv`
- 文件路径：`/data/export/$YYYYMMDD$/$REGION$.dbf` + REGION=EAST → `/data/export/20260525/EAST.dbf`

---

### 4.11 UC-11：执行前稽核

**类**：PreAuditor

**职责**：执行前校验记录数量

**上传场景**：
```java
public boolean audit(Map<String, Object> command, Map<String, Object> config,
                      FtpClient ftp, String filePath) {
    Integer auditCount = (Integer) command.get("稽核数");
    if (auditCount == null || auditCount < 0) {
        return true; // 无需稽核
    }

    // 1. 统计文件记录数
    int fileRecords = countFileRecords(ftp, filePath, config);

    // 2. 比对
    if (fileRecords != auditCount) {
        log.warn("前稽核失败: 文件记录数{} != 稽核数{}", fileRecords, auditCount);
        return false;
    }
    return true;
}
```

**下发场景**：
```java
public boolean audit(Map<String, Object> command, Map<String, Object> config) {
    Integer auditCount = (Integer) command.get("稽核数");
    if (auditCount == null || auditCount < 0) {
        return true;
    }

    // 1. 执行SQL统计筛选后的数据库表记录数
    int dbRecords = dbReader.countWithFilter(config);

    // 2. 比对
    if (dbRecords != auditCount) {
        log.warn("前稽核失败: 数据库记录数{} != 稽核数{}", dbRecords, auditCount);
        return false;
    }
    return true;
}
```

---

### 4.12 UC-12：执行后稽核

**类**：PostAuditor

**职责**：执行后再次校验记录数量

**上传场景**：
```java
public boolean auditAfterUpload(File tempFile, Map<String, Object> config) {
    // 1. 重新统计文件记录数
    int fileRecords = converter.countRecords(new FileInputStream(tempFile), config);

    // 2. 执行SQL统计目标表记录数
    String tableName = config.get("数据库表名");
    int tableRecords = dbReader.count(tableName);

    // 3. 比对
    if (fileRecords != tableRecords) {
        log.error("后稽核失败: 文件记录数{} != 表记录数{}", fileRecords, tableRecords);
        return false;
    }
    return true;
}
```

**下发场景**：
```java
public boolean auditAfterDownload(String filePath, Map<String, Object> config) {
    // 1. 统计生成文件的记录数
    int fileRecords = converter.countRecords(ftp.read(filePath), config);

    // 2. 执行SQL统计源表记录数
    int dbRecords = dbReader.countWithFilter(config);

    // 3. 比对
    if (fileRecords != dbRecords) {
        log.error("后稽核失败: 文件记录数{} != 数据库记录数{}", fileRecords, dbRecords);
        return false;
    }
    return true;
}
```

---

### 4.13 UC-14：错误文件处理

**类**：ErrorFileHandler

**职责**：上传失败文件移动到error目录

```java
public void handle(Map<String, Object> command, Map<String, Object> config,
                   FtpClient ftp, String filePath) {
    // 1. 获取错误目录路径（与源文件同层级）
    String errorDir = filePath.getParent() + "/error";

    // 2. 如果不存在则创建
    if (!ftp.exists(errorDir)) {
        ftp.makeDirectory(errorDir);
    }

    // 3. 拼接错误文件名：原文件名_时间戳.扩展名
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    String errorFileName = filePath.getFileName() + "_" + timestamp + filePath.getExtension();
    String errorFilePath = errorDir + "/" + errorFileName;

    // 4. 移动失败文件到error目录
    ftp.rename(filePath, errorFilePath);

    // 5. 查找并移动对应的标志文件（如果存在）
    String flagFile = findFlagFile(filePath);
    if (flagFile != null) {
        String errorFlagFile = errorDir + "/" + flagFile.getFileName() + "_" + timestamp;
        ftp.rename(flagFile, errorFlagFile);
    }

    log.warn("错误文件已移动: {} -> {}", filePath, errorFilePath);
}
```

**文件名格式**：
- 错误文件：`原文件名_20260522103015.扩展名`
- 时间戳格式：`yyyyMMddHHmmss`

---

### 4.14 UC-15：写日志

**类**：LogManager

**职责**：将处理过程和结果写入本地日志

```java
public void log(String level, Long taskId, String nodeId, String phase,
                String message, Throwable ex) {
    String logLine = String.format(
        "%s %s [TASK-%s] [%s] %s | %s",
        LocalDateTime.now().format(FORMATTER),
        level,
        taskId,
        nodeId,
        phase,
        message
    );

    if (ex != null) {
        logLine += " | " + ex.getMessage();
    }

    // 写入对应级别的日志文件
    String logFile = logPath + "/fmsy_" + LocalDate.now().format(FORMATTER_DATE) + ".log";
    Files.writeString(Path.of(logFile), logLine + "\n", StandardOpenOption.APPEND);
}
```

**日志级别定义**：
- INFO：正常处理信息（任务开始、完成、阶段切换）
- WARN：警告信息（记录跳过、覆盖发生、错误文件移动）
- ERROR：错误信息（连接失败、解析失败、稽核失败）

**日志内容示例**：
```
2026-05-22 10:30:15.123 INFO [TASK-12345] [NODE-A] 任务开始 | 类别=TEST01 | 方向=UPLOAD
2026-05-22 10:30:15.456 INFO [TASK-12345] [NODE-A] 文件解析完成 | 文件=test.csv | 记录数=1000
2026-05-22 10:30:16.789 WARN [TASK-12345] [NODE-A] 标志文件跳过 | 文件=test.csv.OK
2026-05-22 10:30:17.012 WARN [TASK-12345] [NODE-A] 错误文件移动 | 原路径=/data/test.csv | 目标路径=/data/error/test_20260522103017.csv
2026-05-22 10:30:18.123 ERROR [TASK-12345] [NODE-A] 后稽核失败 | 预期=1000 | 实际=999
```

---

### 4.15 UC-16：更新指令状态

**类**：CommandDao.updateStatus()

**职责**：任务处理完成后更新指令表的处理状态和时间

```java
public void updateStatus(Long commandId, String status) {
    String sql = """
        UPDATE 指令表
        SET 处理状态=?, 处理结束时间=NOW()
        WHERE 自增列=?
        """;
    jdbc.update(sql, status, commandId);
}
```

**后置条件**：
- 指令状态更新完成
- 调用UC-19写入结果表

---

### 4.16 UC-17：检查串行约束

**类**：SerialConstraint

**职责**：内存中检查串行约束，避免同一代号指令并发执行

**内存Map结构**：
```java
public class ProcessingInfo {
    Set<String> nodes;         // 正在执行的节点列表
    boolean hasSType;         // 是否有S类型子指令在执行
}

private final Map<String, ProcessingInfo> processingMap = new ConcurrentHashMap<>();
// key = 类别代号 + 控制代号
```

**检查逻辑**：
```java
public boolean check(Map<String, Object> command) {
    String category = (String) command.get("类别代号");
    String ctrl = (String) command.get("控制代号");
    String cmdType = (String) command.get("指令类型");
    String nodeId = getNodeId();

    String key = category + ctrl;
    ProcessingInfo info = processingMap.get(key);

    if (info == null) {
        return true; // 无冲突
    }

    // S类型指令检查
    if ("S".equals(cmdType)) {
        // 允许同节点处理主指令和S类型子指令
        return !info.hasSType || info.nodes.contains(nodeId);
    }

    // 普通指令：检查串行标识
    // 从配置中获取串行标识
    boolean isSerial = configService.isSerial(category, ctrl);
    if (isSerial) {
        // 串行：检查是否有其他节点在执行
        return info.nodes.isEmpty() || info.nodes.contains(nodeId);
    } else {
        // 非串行：检查节点自身是否有同代号指令在执行
        return !info.nodes.contains(nodeId);
    }
}
```

**更新时机**：UC-01每次轮询时，从数据库加载正在处理的指令信息更新Map

---

### 4.17 UC-18：生成下发目录

**类**：DirectoryCreator

**职责**：多文件下发时创建目录

```java
public String createDirectory(Map<String, Object> config, FtpClient ftp) {
    // 1. 解析文件路径，提取目录部分
    String filePath = (String) config.get("文件路径");
    String dirPath = extractDirectoryPath(filePath);

    // 2. 如果配置不允许覆盖，检查总标志文件是否存在
    if ("N".equals(config.get("覆盖标识"))) {
        String flagFile = dirPath + ".flg";
        if (ftp.exists(flagFile)) {
            throw new RuntimeException("总标志文件已存在，不允许覆盖");
        }
    }

    // 3. 在FTP上创建目录
    ftp.createDirectory(dirPath);

    return dirPath;
}
```

**目录路径解析**：
```java
private String extractDirectoryPath(String filePath) {
    int lastSlash = filePath.lastIndexOf('/');
    if (lastSlash < 0) {
        lastSlash = filePath.lastIndexOf('\\');
    }
    return lastSlash >= 0 ? filePath.substring(0, lastSlash) : "";
}
```

---

### 4.18 UC-19：写入结果表

**类**：ResultWriter

**职责**：任务完成后写入结果表

```java
public void write(Long commandId, String category, String ctrl,
                  Map<String, Object> command, Map<String, Object> config,
                  String result, long elapsedMs, int recordCount,
                  long fileSize, String description) {
    String sql = """
        INSERT INTO 结果表 (
            指令ID, 类别代号, 控制代号, FTP名称, 文件路径,
            数据库信息, 传输日期, 处理结果, 处理起始时间,
            处理耗时ms, 数据记录数量, 文件大小, 结果说明
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    jdbc.update(sql,
        commandId,
        category,
        ctrl,
        config.get("FTP名称"),
        config.get("文件路径"),
        config.get("数据库表名"),
        LocalDate.now(),
        result,
        LocalDateTime.now().minusNanos(elapsedMs * 1_000_000),
        elapsedMs,
        recordCount,
        fileSize,
        description
    );
}
```

**分桶下发场景**：
- 每个S类型子指令完成时，插入一条结果记录
- 主指令全部完成时，插入一条主指令的结果记录

---

### 4.19 UC-21：监控子指令完成

**类**：MultiNodeDownloader.monitorAndFinalize()

**职责**：主节点监控S类型子指令完成情况

```java
public DownloadResult monitorAndFinalize(Map<String, Object> command) {
    Long commandId = (Long) command.get("自增列");

    // 1. 等待所有S类型子指令完成
    while (true) {
        int pending = commandDao.countPendingSType(commandId);
        if (pending == 0) {
            break;
        }
        Thread.sleep(1000); // 等待1秒后再检查
    }

    // 2. 汇总所有分桶的处理结果
    List<Map<String, Object>> bucketResults = detailDao.queryByCommandId(commandId);
    String finalStatus = aggregateBucketStatus(bucketResults);

    // 3. 更新主指令状态
    commandDao.updateStatus(commandId, finalStatus);

    // 4. 写入主指令的结果记录
    resultWriter.write(command, finalStatus);

    // 5. 生成总标志文件（如需要）
    if ("Y".equals(finalStatus)) {
        postProcessor.generateTotalFlag(config, ftp, dirPath);
    }

    return new DownloadResult(finalStatus);
}
```

---

### 4.20 UC-22：应用启动

**类**：FmsyApplication + StartupService

**流程**：
```java
@PostConstruct
public void init() {
    // 1. 初始化数据库连接池
    initDataSource();

    // 2. 初始化FTP连接池
    initFtpPool();

    // 3. 加载传输配置（UC-03）
    configLoader.loadConfig();

    // 4. 检查并恢复异常中断的指令
    recoverAbnormalCommands();

    // 5. 启动轮询线程
    pollingService.start();

    log.info("FMSY Application Started");
}

private void recoverAbnormalCommands() {
    // 查询当前节点或节点字段为空的处理中作业
    String sql = """
        SELECT 自增列, 类别代号, 控制代号, 指令类型, 处理节点
        FROM 指令表
        WHERE 处理状态='P'
          AND (处理节点=? OR 处理节点 IS NULL)
        """;
    List<Map<String, Object>> abnormalCommands = jdbc.queryForList(sql, nodeId);

    for (Map<String, Object> cmd : abnormalCommands) {
        // 更新状态为'N'（跳过）
        jdbc.update("UPDATE 指令表 SET 处理状态='N' WHERE 自增列=?", cmd.get("自增列"));

        // 插入结果记录
        resultWriter.write(cmd.get("自增列"), "N", "异常中断恢复，跳过");
    }
}
```

---

### 4.21 UC-23：应用退出

**类**：FmsyApplication

**流程**：
```java
@PreDestroy
public void shutdown() {
    log.info("FMSY Application Shutting Down...");

    // 1. 停止轮询（不再接受新任务）
    pollingService.stop();

    // 2. 查询当前节点正在处理中的作业数量
    int processingCount = commandDao.countProcessing(nodeId);

    // 3. 如果有正在处理的作业，等待完成
    if (processingCount > 0) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log.warn("等待超时，强制中断");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // 4. 关闭FTP连接池
    ftpPool.close();

    // 5. 关闭数据库连接池
    dataSource.close();

    log.info("FMSY Application Shutdown Complete");
}
```

---

## 5. 数据库访问设计

### 5.1 JdbcTemplate封装

```java
public class JdbcTemplateHelper {
    private JdbcTemplate jdbc;

    public int competeUpdate(String sql, Object... params) {
        return jdbc.update(sql, params);
    }

    public List<Map<String, Object>> queryForList(String sql, Object... params) {
        return jdbc.queryForList(sql, params);
    }

    public Map<String, Object> queryForMap(String sql, Object... params) {
        return jdbc.queryForMap(sql, params);
    }

    public int[] batchUpdate(String sql, List<Object[]> paramsList) {
        return jdbc.batchUpdate(sql, paramsList);
    }
}
```

### 5.2 关键SQL汇总

#### 5.2.1 指令表操作

| 操作 | SQL |
|------|-----|
| 获取就绪指令 | `SELECT 自增列, 类别代号, 控制代号, 指令类型, 稽核数, 额外信息 FROM 指令表 WHERE 处理状态='空' AND 处理节点 IS NULL ORDER BY 自增列 ASC LIMIT 20` |
| 加载处理中指令 | `SELECT 处理节点, 类别代号, 控制代号, 指令类型 FROM 指令表 WHERE 处理状态='P' AND 处理节点 IS NOT NULL` |
| 竞争指令 | `UPDATE 指令表 SET 处理节点=?, 处理起始时间=NOW(), 处理状态='P' WHERE 自增列=? AND 处理状态='空' AND 处理节点 IS NULL` |
| 超时检测 | `UPDATE 指令表 SET 处理状态='E', 处理结束时间=NOW() WHERE 处理状态='P' AND 处理节点=? AND 处理起始时间 < NOW() - INTERVAL 1 HOUR` |
| 插入S类型子指令 | `INSERT INTO 指令表 (类别代号, 控制代号, 指令类型, 额外信息, 稽核数) VALUES (?, ?, 'S', ?, -1)` |
| 更新指令状态 | `UPDATE 指令表 SET 处理状态=?, 处理结束时间=NOW() WHERE 自增列=?` |
| 恢复异常中断指令 | `UPDATE 指令表 SET 处理状态='N' WHERE 处理状态='P' AND (处理节点=? OR 处理节点 IS NULL)` |

#### 5.2.2 明细表操作

| 操作 | SQL |
|------|-----|
| 竞争分桶 | `UPDATE 明细表 SET 处理节点=?, 处理状态='P' WHERE 自增列=? AND 处理状态='空'` |
| 查询就绪分桶 | `SELECT 自增列, 指定字段名称, 指定字段取值, 指定文件名, 稽核数 FROM 明细表 WHERE 对应指令ID=? AND 处理状态='空' ORDER BY 自增列 ASC LIMIT ?` |
| 插入分桶记录 | `INSERT INTO 明细表 (对应指令ID, 类别代号, 控制代号, 指定字段名称, 指定字段取值, 处理状态) VALUES (?, ?, ?, ?, ?, '空')` |
| 更新分桶状态 | `UPDATE 明细表 SET 处理状态=?, 处理节点=? WHERE 自增列=?` |
| 批量更新分桶状态 | `UPDATE 明细表 SET 处理状态=? WHERE 对应指令ID=? AND 处理状态='空'` |

#### 5.2.3 数据读写操作

| 操作 | SQL |
|------|-----|
| **插入文件数据到数据库** | `INSERT INTO {表名} ({列1}, {列2}, ...) VALUES ({值1}, {值2}, ...)` |
| **批量插入文件数据** | `INSERT INTO {表名} ({列1}, {列2}, ...) VALUES (?, ?, ...), (?, ?, ...), ...` |
| **批次读取源表数据** | `SELECT {字段列表} FROM {表名} WHERE {拆分字段}=? LIMIT ? OFFSET ?` |
| **读取源表所有数据** | `SELECT {字段列表} FROM {表名} ORDER BY {排序字段}` |
| **统计记录数** | `SELECT COUNT(*) FROM {表名} WHERE {拆分字段}=?` |
| **按分桶字段分组查询** | `SELECT DISTINCT {拆分字段} FROM {表名} WHERE {拆分字段} IS NOT NULL` |

**插入数据SQL动态拼装示例**：

```java
// 场景：上传文件解析后插入数据库
// 表字段：A, B, C, D
// 忽略字段配置：C
// 明细表指定字段名称：B,E，指定字段取值：val1,val2

// Step1: 获取数据库表字段元数据 → [A, B, C, D]
// Step2: 应用忽略字段配置 → 去除C → [A, B, D]
// Step3: 应用明细表指定字段 → 去除B,E → [A, D]
// Step4: 读取数据文件 → [vA, vC]
// Step5: 对齐后 → A=vA, D=vC
// Step6: 补充指定字段 → A=vA, B=val1, D=vC, E=val2

// 最终INSERT
INSERT INTO {表名} (A, B, D, E) VALUES (?, ?, ?, ?)
// 参数: [vA, val1, vC, val2]
```

**批次读取SQL动态拼装示例**：

```java
// 场景：下发时批次读取数据库
// 配置：拆分字段=REGION，每批100条

String sql = "SELECT * FROM " + tableName + " WHERE REGION=? ORDER BY id LIMIT ? OFFSET ?";

// 分页参数
int batchSize = 100;
int offset = 0;
while (true) {
    List<Map<String, Object>> batch = jdbc.queryForList(sql, regionValue, batchSize, offset);
    if (batch.isEmpty()) break;
    // 处理批次
    converter.generate(output, batch.iterator(), config);
    offset += batchSize;
}
```

**分区表读取SQL**：

```sql
-- 查询表是否为分区表（GaussDB/Oracle）
SELECT PARTITIONED FROM USER_TABLES WHERE TABLE_NAME = UPPER(?)

-- 查询分区列表
SELECT PARTITION_NAME FROM USER_TAB_PARTITIONS
WHERE TABLE_NAME = UPPER(?) AND PARTITION_NAME IS NOT NULL

-- 按分区查询数据
SELECT * FROM {表名} PARTITION ({分区名}) WHERE {拆分字段}=?
```

**分区表遍历读取示例**：
```java
// 场景：下发时源表为分区表，按REGION字段拆分
// 分区模式：AUTO（自动探测）

public List<Map<String, Object>> readWithPartition(Map<String, Object> config) {
    String tableName = config.get("数据库表名");

    // 1. 探测是否为分区表
    Map<String, Object> meta = dbMetadata.getTableMetadata(tableName);
    boolean isPartitioned = "TRUE".equals(meta.get("PARTITIONED"));

    if (!isPartitioned) {
        // 非分区表：直接读取
        return read(config);
    }

    // 2. 获取分区列表
    List<String> partitions = dbMetadata.getPartitions(tableName);

    // 3. 按分区遍历读取
    List<Map<String, Object>> allData = new ArrayList<>();
    for (String partition : partitions) {
        String sql = "SELECT * FROM " + tableName + " PARTITION (" + partition + ")";
        List<Map<String, Object>> partitionData = jdbc.queryForList(sql);
        allData.addAll(partitionData);
    }
    return allData;
}
```

#### 5.2.4 结果表操作

| 操作 | SQL |
|------|-----|
| 写入结果表 | `INSERT INTO 结果表 (指令ID, 类别代号, 控制代号, FTP名称, 文件路径, 数据库信息, 传输日期, 处理结果, 处理起始时间, 处理耗时ms, 数据记录数量, 文件大小, 结果说明) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)` |
| 查询指令结果 | `SELECT * FROM 结果表 WHERE 指令ID=?` |

---

## 6. 转换器插件设计

### 6.1 FileConverter接口

```java
public interface FileConverter {
    String getFormat(); // DBF/XML/CSV/TXT

    Iterator<List<Map<String, Object>>> parse(InputStream input,
                                               Map<String, Object> config);

    void generate(OutputStream output,
                  Iterator<List<Map<String, Object>>> data,
                  Map<String, Object> config);

    int countRecords(InputStream input, Map<String, Object> config);
}
```

### 6.2 转换器对比

| 转换器 | 解析模式 | 生成模式 | 配置参数 |
|--------|----------|----------|----------|
| DBFConverter | 定长记录读取 | 定长记录写入 | encoding |
| XMLConverter | SAX解析 | 流式生成 | encoding, rootElement |
| CSVConverter | 分隔符解析 | 分隔符生成 | encoding, separator, quote |
| TXTConverter | 分隔符/定长 | 分隔符/定长 | encoding, mode, separator, fieldWidths |

### 6.3 流式处理原则

**上传场景**：
```java
// 边读文件边解析，分批次写入数据库
Iterator<List<Map<String, Object>>> data = converter.parse(input, config);
while (data.hasNext()) {
    List<Map<String, Object>> batch = data.next();
    dbWriter.writeBatch(batch); // 每批次独立事务
}
```

**下发场景**：
```java
// 边读数据库边生成文件，分批次写入
Iterator<List<Map<String, Object>>> data = dbReader.readBatch(config);
converter.generate(output, data, config); // 流式写入文件
```

---

## 7. FTP连接池设计

### 7.1 连接池配置

```yaml
ftp:
  pool:
    max-total: 20
    min-idle: 5
    max-wait-millis: 30000
    test-on-borrow: true
    test-on-return: false
```

### 7.2 FTP操作封装

```java
public class FtpOperator {
    private FtpPool pool;

    public InputStream download(String path) throws IOException {
        FtpClient client = pool.borrowObject();
        return client.retrieveFileStream(path);
    }

    public void upload(String path, InputStream data) throws IOException {
        FtpClient client = pool.borrowObject();
        client.storeFile(path, data);
        pool.returnObject(client);
    }

    public boolean exists(String path) throws IOException {
        FtpClient client = pool.borrowObject();
        boolean exists = client.exists(path);
        pool.returnObject(client);
        return exists;
    }

    public void delete(String path) throws IOException {
        FtpClient client = pool.borrowObject();
        client.delete(path);
        pool.returnObject(client);
    }
}
```

---

## 8. 状态值定义

### 8.1 状态枚举

| 取值 | 名称 | 说明 |
|------|------|------|
| 空 | 就绪 | 插入后的初始状态 |
| P | 处理中 | 竞争后被处理中 |
| Y | 成功 | 完全成功 |
| N | 跳过 | 标志文件未到达或空记录不允许发空文件 |
| E | 异常 | 转换过程发生异常导致未完成 |

### 8.2 各表状态字段说明

**指令表.处理状态**：

| 状态 | 说明 |
|------|------|
| 空 | 就绪，等待被执行 |
| P | 处理中，正在执行 |
| Y | 完成，完全成功 |
| N | 跳过，标志文件未到达或空记录不允许发空文件 |
| E | 失败，发生异常 |

**明细表.处理状态**：

| 状态 | 说明 |
|------|------|
| 空 | 就绪，等待被执行（分桶未分配） |
| P | 处理中，正在生成分桶文件 |
| Y | 成功，分桶文件已生成 |
| N | 跳过，分桶数据为空且不允许发空文件 |
| E | 异常，分桶文件生成失败或回滚 |

**结果表.处理结果**：

| 状态 | 说明 |
|------|------|
| Y | 成功 |
| N | 跳过，标志文件未到达或空记录不允许发空文件 |
| E | 异常，转换过程发生异常或回滚失败 |

---

## 9. 部署架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Load Balancer                          │
└─────────────────────────────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
    ┌─────────┐     ┌─────────┐     ┌─────────┐
    │ Node A  │     │ Node B  │     │ Node C  │
    │ FMSY    │     │ FMSY    │     │ FMSY    │
    └────┬────┘     └────┬────┘     └────┬────┘
         │               │               │
         └───────────────┼───────────────┘
                         ▼
              ┌─────────────────┐
              │    GaussDB      │
              └─────────────────┘
                         │
              ┌─────────────────┐
              │      FTP        │
              └─────────────────┘
```

**多节点部署要点**：
- 节点ID使用hostname标识
- 各节点独立轮询，竞争获取指令
- 串行约束通过内存Map协调
- 节点故障时，超时检测自动释放任务

---

## 10. 性能参数建议

| 参数 | 默认值 | 可配置范围 | 说明 |
|------|--------|------------|------|
| 轮询间隔 | 10秒 | 5-300秒 | - |
| 每批次获取指令数 | 20 | 50-100 | 高吞吐场景可增大 |
| 单节点并发线程数 | 10 | 10-20 | - |
| 单文件多线程上传并发数 | 3 | - | 避免事务爆炸 |
| 分桶每批获取数 | 3 | - | - |
| 任务超时时间 | 1小时 | - | 自动释放 |
| FTP连接池最大连接数 | 20 | - | - |

---

## 11. 附录

### 11.1 配置示例（application.yml）

```yaml
spring:
  datasource:
    url: jdbc:gaussdb://192.168.1.100:5432/fmsy
    username: fmsy_user
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000

ftp:
  pool:
    max-total: 20
    min-idle: 5
    max-wait-millis: 30000

app:
  node-id: ${HOSTNAME}
  polling:
    interval: 10000
    batch-size: 20
  upload:
    thread-count: 3
  download:
    bucket-batch-size: 3
  timeout:
    task-millis: 3600000
  log:
    path: /var/log/fmsy
    level: INFO
```

### 11.2 用例-组件对照表

| 用例ID | 用例名称 | 核心组件 |
|--------|----------|----------|
| UC-01 | 轮询指令表 | PollingService |
| UC-02 | 竞争指令执行权 | CompetitionService |
| UC-03 | 加载传输配置 | ConfigLoader |
| UC-04 | 查询传输配置 | ConfigService |
| UC-05 | 执行上传任务 | SingleUploader/MultiUploader/BatchUploader |
| UC-06 | 执行下发任务 | SingleDownloader/MultiDownloader/MultiNodeDownloader |
| UC-07 | 轮询分桶明细 | BucketService |
| UC-08 | 校验标志文件 | PreFileChecker |
| UC-09 | 处理标志文件 | PostFileProcessor |
| UC-10 | 解析占位符 | PlaceholderResolver |
| UC-11 | 执行前稽核 | PreAuditor |
| UC-12 | 执行后稽核 | PostAuditor |
| UC-14 | 错误文件处理 | ErrorFileHandler |
| UC-15 | 写日志 | LogManager |
| UC-16 | 更新指令状态 | CommandDao |
| UC-17 | 检查串行约束 | SerialConstraint |
| UC-18 | 生成下发目录 | DirectoryCreator |
| UC-19 | 写入结果表 | ResultWriter |
| UC-21 | 监控子指令完成 | MultiNodeDownloader |
| UC-22 | 应用启动 | StartupService |
| UC-23 | 应用退出 | FmsyApplication |

---

**文档结束**