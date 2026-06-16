# FMSY 系统设计文档 V2.0

## 文档信息

| 项目 | 内容 |
|------|------|
| 项目名称 | FMSY（File Transfer Management System） |
| 版本 | V2.0 |
| 日期 | 2026-05-26 |
| 状态 | 正式版 |

### 变更记录

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| V2.0 | 2026-05-26 | 基于需求规格说明V1.5编写完整系统设计文档 |

---

## 1. 技术选型

### 1.1 技术栈

| 类别 | 技术选型 | 说明 |
|------|----------|------|
| 基础框架 | Spring Boot 3.x + JDK 21 | 现代化Java生态系统，支持模块化配置 |
| 数据库访问 | JdbcTemplate简化封装 | 轻量级封装，支持动态SQL拼装 |
| 连接池 | HikariCP风格FTP连接池 | 共享FTP连接，支持高效复用 |
| 并发模型 | ThreadPool + @Async | 任务异步处理，线程池管理 |
| 包结构 | 按领域组件划分 | polling/converter/ftphandler等 |
| 事务边界 | 文件与数据库分离 | 独立事务，避免交叉污染 |

### 1.2 技术选型理由

**Spring Boot 3.x + JDK 21**：
- 模块化配置简化环境切换
- 内置健康检查、优雅关闭
- 未来可扩展为Spring Native

**JdbcTemplate简化封装**：
- 轻量级，比JPA/Hibernate更适合复杂SQL场景
- 便于动态SQL拼装（INSERT动态字段、批次查询分页）
- 与GaussDB兼容性良好

**FTP连接池共享**：
- 避免每次操作建立连接的开销
- HikariCP风格确保连接复用和高性能
- 支持多FTP配置（不同ID对应不同连接）

**ThreadPool + @Async**：
- 指令竞争成功后异步处理，提升吞吐量
- 便于控制并发数（配置参数）
- 支持优雅关闭（等待任务完成）

---

## 2. 系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           FMSY Application                               │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────┐ │
│  │  Config Loader  │  │  Command Polling │  │   Converter Engine      │ │
│  │   (UC-03)       │  │    (UC-01)       │  │                         │ │
│  └────────┬────────┘  └────────┬────────┘  └────────────┬──────────────┘ │
│           │                    │                        │                │
│           ▼                    ▼                        ▼                │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                    Plugin Architecture                             │  │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐              │  │
│  │  │   DBF   │  │   XML   │  │   CSV   │  │   TXT   │              │  │
│  │  │Converter│  │Converter│  │Converter│  │Converter│              │  │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘              │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                    │                        │                            │
│                    ▼                        ▼                            │
│              ┌──────────┐             ┌──────────┐                      │
│              │  GaussDB │             │    FTP   │                      │
│              │ (JDBC)   │             │ (Pool)   │                      │
│              └──────────┘             └──────────┘                      │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 分层设计

```
┌────────────────────────────────────────────────────────┐
│                  Application Layer                      │
│  ┌──────────────────┐  ┌────────────────────────────┐   │
│  │  Spring Boot     │  │  @Async Task Executor      │   │
│  │  Entry Points    │  │  (ThreadPoolTaskExecutor)  │   │
│  └──────────────────┘  └────────────────────────────┘   │
├────────────────────────────────────────────────────────┤
│                    Service Layer                        │
│  ┌────────────┐  ┌────────────┐  ┌────────────────┐    │
│  │ Polling    │  │ Transfer   │  │ Audit          │    │
│  │ Service    │  │ Service    │  │ Service        │    │
│  └────────────┘  └────────────┘  └────────────────┘    │
├────────────────────────────────────────────────────────┤
│                   Component Layer                       │
│  ┌────────────┐  ┌────────────┐  ┌────────────────┐    │
│  │ Command    │  │ File       │  │ FTP            │    │
│  │ Compete    │  │ Operations  │  │ Handler        │    │
│  └────────────┘  └────────────┘  └────────────────┘    │
├────────────────────────────────────────────────────────┤
│                    Plugin Layer                         │
│  ┌────────────┐  ┌────────────┐  ┌────────────────┐    │
│  │ DBF       │  │ XML        │  │ CSV            │    │
│  │ Converter │  │ Converter   │  │ Converter      │    │
│  └────────────┘  └────────────┘  └────────────────┘    │
├────────────────────────────────────────────────────────┤
│                  Infrastructure Layer                    │
│  ┌────────────┐  ┌────────────┐  ┌────────────────┐    │
│  │ Database   │  │ FTP Pool   │  │ Log            │    │
│  │ (JdbcTemplate) │ Pool      │  │ Service        │    │
│  └────────────┘  └────────────┘  └────────────────┘    │
└────────────────────────────────────────────────────────┘
```

### 2.3 核心组件交互流程

```
1. [启动] ApplicationRunner → UC-03加载配置 → UC-22异常恢复 → 启动轮询线程
2. [轮询] UC-01定时轮询 → 查询就绪指令 → 加载处理中指令到内存Map
3. [竞争] UC-17串行约束检查 → UC-02原子竞争 → 成功则@Async处理
4. [执行] UC-04查询配置 → UC-05/UC-06执行传输 → UC-11/UC-12稽核
5. [后置] UC-09处理标志文件 → UC-16更新状态 → UC-19写入结果
6. [监控] UC-21监控S类型子指令 → UC-18生成目录/总标志文件
```

---

## 3. 包结构设计

### 3.1 包组织结构

```
com.fmsy
├── FmsyApplication.java                 # Spring Boot启动类
├── config                               # 配置层
│   ├── AppConfig.java                  # 应用配置（轮询间隔、线程数等）
│   ├── DataSourceConfig.java           # 数据库连接配置
│   ├── FtpPoolConfig.java              # FTP连接池配置
│   ├── AsyncConfig.java               # 异步任务配置
│   └── YamlPropertySourceFactory.java # YAML配置加载
├── polling                              # 指令轮询组件
│   ├── PollingService.java            # 轮询服务（UC-01）
│   ├── CommandCompetition.java         # 指令竞争（UC-02）
│   └── SerialConstraintChecker.java   # 串行约束检查（UC-17）
├── transfer                             # 传输执行组件
│   ├── TransferService.java           # 传输服务（UC-05/UC-06）
│   ├── UploadExecutor.java            # 上传执行器
│   ├── DownloadExecutor.java         # 下发执行器
│   ├── BucketDistributor.java         # 分桶分发（DOWNLOAD_MULTI_NODE）
│   └── placeholder                    # 占位符解析
│       └── PlaceholderResolver.java   # UC-10
├── audit                               # 稽核组件
│   ├── PreAuditService.java           # 前稽核（UC-11）
│   └── PostAuditService.java          # 后稽核（UC-12）
├── fileops                             # 文件操作组件
│   ├── FlagFileChecker.java          # 标志文件校验（UC-08）
│   ├── FlagFileProcessor.java        # 标志文件处理（UC-09）
│   ├── ErrorFileHandler.java         # 错误文件处理（UC-14）
│   ├── DirectoryGenerator.java       # 目录生成（UC-18）
│   ├── TotalFlagGenerator.java       # 总标志文件生成（UC-09后置）
│   └── MessageSender.java            # 消息发送（UC-09 SEND_MESSAGE）
├── state                              # 状态更新组件
│   ├── CommandStatusUpdater.java     # 指令表状态更新（UC-16）
│   └── DetailStatusUpdater.java      # 明细表状态更新（UC-16）
├── detail                              # 明细表组件
│   ├── DetailPollingService.java      # 分桶轮询（UC-07）
│   └── DetailQueryService.java       # 明细表查询服务
├── result                              # 结果表组件
│   └── ResultWriter.java             # 结果写入（UC-19）
├── child                               # 子指令监控组件
│   └── ChildCommandMonitor.java      # 子指令监控（UC-21）
├── lifecycle                           # 应用生命周期
│   ├── StartupService.java           # 启动服务（UC-22）
│   └── ShutdownService.java          # 退出服务（UC-23）
├── converter                           # 转换器插件
│   ├── FileConverter.java            # 转换器接口
│   ├── FieldMapping.java             # 字段映射
│   ├── dbfs                          # DBF转换器
│   │   └── DbfConverter.java
│   ├── xml                           # XML转换器
│   │   └── XmlConverter.java
│   ├── csv                           # CSV转换器
│   │   └── CsvConverter.java
│   └── txt                           # TXT转换器
│       └── TxtConverter.java
├── ftp                                # FTP处理
│   ├── FtpPool.java                  # FTP连接池
│   ├── FtpClient.java                # FTP客户端封装
│   └── FtpOperationTemplate.java    # FTP操作模板
├── db                                 # 数据库访问
│   ├── JdbcTemplateWrapper.java      # JdbcTemplate封装
│   ├── SqlBuilder.java               # 动态SQL构建
│   └── PartitionHelper.java          # 分区表辅助（分区遍历）
├── model                               # 数据模型
│   ├── Command.java                 # 指令表实体
│   ├── Detail.java                  # 明细表实体
│   ├── Result.java                  # 结果表实体
│   └── TransferConfig.java           # 传输配置实体
├── enums                              # 枚举定义
│   ├── TransferScenario.java        # 传输场景
│   ├── CommandType.java             # 指令类型
│   ├── ProcessStatus.java           # 处理状态
│   └── EmptyDataHandling.java        # 空数据处理
└── util                               # 工具类
    ├── DateUtils.java               # 日期工具
    ├── LogUtils.java                # 日志工具
    └── ThreadLocalUtils.java        # 线程本地工具
```

### 3.2 核心模块依赖关系

```
polling (UC-01/02/17)
    ├── db (JdbcTemplateWrapper)
    ├── model (Command)
    ├── lifecycle (ShutdownService - 注册关闭钩子)
    └── @Async TaskExecutor

transfer (UC-05/06)
    ├── converter (FileConverterplugins)
    ├── ftp (FtpPool)
    ├── db (JdbcTemplateWrapper)
    ├── fileops (FlagFileChecker, DirectoryGenerator, MessageSender)
    ├── audit (PreAuditService, PostAuditService)
    └── state (CommandStatusUpdater, DetailStatusUpdater)

fileops (UC-08/09/14/18)
    ├── ftp (FtpPool)
    └── model (TransferConfig)

audit (UC-11/12)
    ├── db (JdbcTemplateWrapper)
    └── converter (FileConverter)

state (UC-16)
    └── db (JdbcTemplateWrapper)

child (UC-21)
    ├── polling (CommandCompetition - 竞争S类型子指令)
    ├── detail (DetailPollingService)
    └── result (ResultWriter)
```

### 3.3 新增组件设计

#### 3.3.1 MessageSender组件

**类**: `MessageSender`

**职责**：发送消息（邮件、短信、MQ等），支持UC-09后置操作的SEND_MESSAGE

**功能说明**：
- 支持三种消息发送方式：MAIL、SMS、MQ
- 通过targetConfig参数格式`类型:目标地址`确定发送方式
- MAIL格式：`MAIL:email@example.com`
- SMS格式：`SMS:13800138000`
- MQ格式：`MQ:topic-name`

**接口定义**：

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| send | message, targetConfig | void | 发送消息 |

**实现类**：
- `MailMessageSender` - 邮件发送实现
- `SmsMessageSender` - 短信发送实现
- `MqMessageSender` - 消息队列发送实现

---

#### 3.3.2 CommandStatusUpdater组件

**类**: `CommandStatusUpdater`

**职责**：更新指令表状态，UC-16核心组件

**功能说明**：
- 更新指令的处理状态（Y/N/E/P）
- 批量更新多条指令状态
- 支持将指令标记为处理中（用于重试场景）

**核心方法**：

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| updateStatus | commandId, status | void | 更新单条指令状态 |
| batchUpdateStatus | commandIds, status | void | 批量更新指令状态 |
| markProcessing | commandId, nodeId | void | 标记为处理中 |

**状态更新SQL**：
```sql
UPDATE 指令表 SET 处理状态=?, 指令处理结束时间=NOW() WHERE 自增列=?
```

---

#### 3.3.3 DetailStatusUpdater组件

**类**: `DetailStatusUpdater`

**职责**：更新明细表状态，UC-16组成部分

**功能说明**：
- 更新分桶的处理状态
- 批量更新多条明细状态
- 支持按主指令ID批量更新其下所有分桶

**核心方法**：

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| updateStatus | detailId, status, nodeId | void | 更新单条分桶状态 |
| batchUpdateStatus | detailIds, status, nodeId | void | 批量更新分桶状态 |
| batchUpdateByCommandId | commandId, status, nodeId | void | 按主指令批量更新 |

**状态更新SQL**：
```sql
UPDATE 明细表 SET 处理状态=?, 处理节点=? WHERE 自增列=?
```

---

#### 3.3.4 DetailQueryService组件

**类**: `DetailQueryService`

**职责**：查询明细表，用于UC-07轮询分桶和UC-05/UC-06指定下发

**功能说明**：
- 查询某指令ID下的所有明细记录
- 查询状态为"空"的待处理分桶
- 统计某指令下各状态的明细数量

**核心方法**：

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| queryByCommandId | commandId | List<Detail> | 查询某指令的所有明细 |
| queryReadyBuckets | commandId, limit | List<Detail> | 查询待处理分桶 |
| countByStatus | commandId | Map<Status, Long> | 统计各状态数量 |

**关键SQL**：
```sql
-- 查询就绪分桶
SELECT * FROM 明细表 WHERE 对应指令ID=? AND 处理状态='空' ORDER BY 自增列 LIMIT ?
```

---

### 3.4 模块依赖关系图（补充）

```
┌─────────────────────────────────────────────────────────────────┐
│                      transfer (UC-05/06)                       │
│  调用: converter, ftp, db, fileops, audit, state              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       state (UC-16)                              │
│  CommandStatusUpdater ──► 指令表更新                             │
│  DetailStatusUpdater ──► 明细表更新                              │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. 用例详细设计

### 4.1 UC-01: 轮询指令表

**类**: `PollingService`

**核心流程**:

1. **释放超时任务** - 更新超时1小时的指令状态为'E'
2. **加载处理中指令** - 查询正在处理的指令到内存Map供串行约束检查
3. **查询就绪指令** - 每批默认20条
4. **串行约束检查** - UC-17检查是否允许本节点执行
5. **竞争执行权** - UC-02原子竞争
6. **异步处理** - 竞争成功后由线程池异步执行

**内存Map结构**:
- key: 类别代号+控制代号
- value: ProcessingInfo { 节点ID列表, 是否有S类型子指令 }

**关键SQL**:
```sql
-- 释放超时任务
UPDATE 指令表
SET 处理状态='E', 指令处理结束时间=NOW()
WHERE 处理状态='P' AND 处理节点=? AND 指令处理起始时间 < NOW() - INTERVAL 1 HOUR;

-- 查询正在处理的指令（加载到内存）
SELECT 处理节点, 类别代号, 控制代号, 指令类型
FROM 指令表
WHERE 处理状态='P' AND 处理节点 IS NOT NULL;

-- 查询就绪指令
SELECT 自增列, 类别代号, 控制代号, 指令类型, 稽核数, 额外信息
FROM 指令表
WHERE 处理状态='空' AND 处理节点 IS NULL
ORDER BY 自增列 ASC LIMIT 20;
```

---

### 4.2 UC-02: 竞争指令执行权

**类**: `CommandCompetition`

**核心流程**:

1. **原子竞争SQL** - UPDATE指令表抢执行权
2. **条件判断** - 处理状态='空' AND 处理节点 IS NULL
3. **返回结果** - 影响行数1=成功, 0=失败

---

### 4.3 UC-03: 加载传输配置

**类**: `ConfigLoaderService`

**核心流程**:

1. **查询有效配置** - 从传输配置表加载状态='有效'的配置
2. **存入内存Map** - 按类别代号+控制代号索引
3. **初始化转换器** - 创建各类型转换器实例
4. **验证连接** - 验证数据库和FTP连接可用

**连接验证**:
- 数据库: 执行 `SELECT 1` 验证
- FTP: 获取欢迎信息或列出根目录验证

---

### 4.4 UC-04: 查询传输配置

**类**: `TransferConfigQueryService`

**核心流程**:

1. **拼接配置Key** - 类别代号+控制代号
2. **查询内存Map** - 获取对应传输配置
3. **配置校验** - 不存在则记录错误并返回null

**配置解析内容**:
- 传输场景、文件路径、数据库表名
- 解析器类型、前置/后置文件操作
- 忽略字段配置、拆分字段配置
- FTP名称、数据库名称
- 空数据处理策略

---

### 4.5 UC-05: 执行上传任务

**类**: `UploadExecutor`

**场景分支**:

| 传输场景 | 指令类型 | 处理分支 |
|----------|----------|----------|
| UPLOAD_SINGLE | - | 单文件上传 |
| UPLOAD_MULTI | 空 | 目录多文件上传（匹配多个文件） |
| UPLOAD_MULTI | R | 指定多文件上传（查明细表） |

**单文件上传流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 解析文件路径 | 含占位符替换 |
| 2 | FTP连接 | 从连接池获取客户端 |
| 3 | 前置处理 | CHECK_READY/CHECK_FLAG校验 |
| 4 | 前稽核 | 记录数与稽核数比对 |
| 5 | 解析文件 | 流式解析，分批次处理 |
| 6 | 批次写入 | 动态INSERT，支持清空表 |
| 7 | 后稽核 | 文件记录数与数据库记录数比对 |
| 8 | 后置处理 | GENERATE_FEEDBACK/RENAME/DELETE等 |
| 9 | 返回结果 | SUCCESS/SKIPPED/ERROR |

**目录多文件上传流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 解析目录路径 | 含占位符替换 |
| 2 | 列出匹配文件 | FTP列出目录下匹配模式的文件 |
| 3 | 并行处理 | 线程数=MIN(文件数, 配置并发数) |
| 4 | 汇总结果 | 全部成功/全部跳过/部分成功 |

**指定多文件上传流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 查询明细表 | 获取指定文件列表或分桶取值 |
| 2 | 遍历明细 | 按记录逐个处理 |
| 3 | 填充占位符 | 用明细表指定字段生成文件路径 |
| 4 | 执行上传 | 复用单文件上传流程 |
| 5 | 更新明细状态 | 记录处理结果 |
| 6 | 汇总指令状态 | 根据明细状态汇总 |

---

### 4.6 UC-06: 执行下发任务

**类**: `DownloadExecutor`

**场景分支**:

| 传输场景 | 指令类型 | 处理分支 |
|----------|----------|----------|
| DOWNLOAD_SINGLE | - | 单文件下传 |
| DOWNLOAD_SINGLE_NODE | 空 | 单节点多文件下传（全表拆分） |
| DOWNLOAD_SINGLE_NODE | R | 单节点多文件下传（指定拆分） |
| DOWNLOAD_MULTI_NODE | 空/R | 多节点协调拆分下传（创建S类型子指令） |

**单文件下传流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 解析文件路径 | 含占位符替换 |
| 2 | 前置处理 | CHECK_READY/CHECK_FLAG校验 |
| 3 | 前稽核 | 数据库记录数与稽核数比对 |
| 4 | 查询数据库 | 动态SQL拼装，支持忽略字段 |
| 5 | 空数据处理 | 按配置策略处理 |
| 6 | 生成文件 | 流式生成到FTP |
| 7 | 后置处理 | GENERATE_FEEDBACK/RENAME/DELETE等 |

**单节点多文件下传（全表）流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 创建目录 | UC-18创建下发目录 |
| 2 | 查询分桶取值 | 按拆分字段查询不重复值 |
| 3 | 遍历分桶 | 对每个分桶值生成文件 |
| 4 | 填充占位符 | 用分桶值替换$FIELD$ |
| 5 | 查询数据 | 按分桶条件筛选 |
| 6 | 生成文件+标志 | 每个分桶独立文件+子标志 |
| 7 | 生成总标志 | UC-09后置 |

**多节点协调拆分下传流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 初始化 | 根据指令类型(R/空)决定行为 |
| 2 | 创建分桶记录 | 查询全部分桶值，插入明细表 |
| 3 | 创建S类型子指令 | 生成M条子指令 |
| 4 | 各节点竞争处理 | UC-07轮询分桶明细处理 |
| 5 | 监控完成 | UC-21监控所有S类型子指令 |

---

### 4.7 UC-07: 轮询分桶明细

**类**: `DetailPollingService`

**核心流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 竞争分桶 | 原子更新明细表状态为'P' |
| 2 | 前稽核 | 分桶级别的数据校验 |
| 3 | 查询数据 | 按分桶字段筛选 |
| 4 | 生成文件 | 流式生成到FTP |
| 5 | 生成子标志 | 分桶处理完成标志 |
| 6 | 后稽核 | 最终数据校验 |
| 7 | 更新状态 | Y=成功/N=跳过/E=异常 |
| 8 | 更新子指令 | 所有分桶完成后标记子指令 |

**竞争SQL**:
```sql
UPDATE 明细表
SET 处理节点=?, 处理状态='P'
WHERE 自增列=? AND 处理状态='空'
```

---

### 4.8 UC-08: 校验标志文件

**类**: `FlagFileChecker`

**核心流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 解析操作列表 | 解析CHECK_READY/CHECK_FLAG |
| 2 | CHECK_READY | 验证文件是否存在 |
| 3 | CHECK_FLAG | 读取标志文件并校验内容 |
| 4 | 返回结果 | PASS/SKIP/ERROR |

---

### 4.9 UC-09: 处理标志文件

**类**: `FlagFileProcessor`

**核心流程**:

| 步骤 | 操作类型 | 说明 |
|------|---------|------|
| 1 | DELETE | 删除指定文件 |
| 2 | RENAME | 重命名文件 |
| 3 | GENERATE_FEEDBACK | 生成反馈文件 |
| 4 | GENERATE_SUB_FLAG | 生成分桶标志（多节点下发用） |
| 5 | GENERATE_TOTAL_FLAG | 生成总标志文件 |
| 6 | SEND_MESSAGE | 发送消息（邮件/短信/MQ） |

**MessageSender组件设计**:
- 职责：发送消息（邮件、短信、MQ等）
- 对外接口：`send(message, targetConfig)`
- 实现：根据targetConfig确定发送方式（MAIL:/SMS:/MQ:）

---

### 4.10 UC-10: 解析占位符

**类**: `PlaceholderResolver`

**核心方法**:

| 方法 | 说明 |
|------|------|
| resolve | 解析日期占位符($YYYYMMDD$/$YYYYMMDDHHmmss$)和指令字段($EXTRA_INFO$) |
| resolveWithDetail | 额外替换明细表指定的字段占位符 |
| resolveWithBucket | 额外替换分桶字段占位符 |

---

### 4.11 UC-11: 执行前稽核

**类**: `PreAuditService`

**上传场景**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 获取稽核数 | 指令表的稽核数字段 |
| 2 | 统计文件记录数 | FTP获取文件行数 |
| 3 | 比对 | 文件记录数=稽核数则通过 |

**下发场景**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 获取稽核数 | 指令表的稽核数字段 |
| 2 | 统计数据库记录数 | SELECT COUNT(*) |
| 3 | 比对 | 记录数=稽核数则通过 |

---

### 4.12 UC-12: 执行后稽核

**类**: `PostAuditService`

**上传场景**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 重新统计文件记录数 | FTP获取文件行数 |
| 2 | 统计数据库记录数 | SELECT COUNT(*) |
| 3 | 比对 | 两者相等则通过 |

**下发场景**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 统计生成文件记录数 | 解析生成的文件 |
| 2 | 统计源表记录数 | SELECT COUNT(*) |
| 3 | 比对 | 两者相等则通过 |

---

### 4.13 UC-14: 错误文件处理

**类**: `ErrorFileHandler`

**核心流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 创建error目录 | 在原文件目录下创建error子目录 |
| 2 | 移动文件 | 重命名到error目录并添加时间戳 |
| 3 | 移动标志文件 | 如果存在则一并移动 |

---

### 4.14 UC-15: 写日志

**实现**: 使用SLF4J + Logback

**日志格式**:
```
2026-05-26 10:30:15.123 INFO  [TASK-12345] [NODE-A] 任务开始 | 类别=TEST01 | 方向=UPLOAD
2026-05-26 10:30:15.456 INFO  [TASK-12345] [NODE-A] 文件解析完成 | 文件=test.csv | 记录数=1000
2026-05-26 10:30:16.789 WARN  [TASK-12345] [NODE-A] 标志文件跳过 | 文件=test.csv.OK
2026-05-26 10:30:17.012 WARN  [TASK-12345] [NODE-A] 错误文件移动 | 原路径=/data/test.csv | 目标路径=/data/error/test_20260526103017.csv
2026-05-26 10:30:18.123 ERROR [TASK-12345] [NODE-A] 后稽核失败 | 预期=1000 | 实际=999
```

**日志配置** (`logback-spring.xml`):
```xml
<appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${LOG_PATH}/fmsy.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>${LOG_PATH}/fmsy_%d{yyyyMMdd}.log</fileNamePattern>
        <maxHistory>30</maxHistory>
    </rollingPolicy>
    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [TASK-%X{taskId}] [NODE-%X{nodeId}] %msg%n</pattern>
    </encoder>
</appender>
```

---

### 4.15 UC-16: 更新指令状态

**类**: `CommandStatusUpdater`

**核心流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 更新指令状态 | UPDATE指令表 SET 处理状态=?, 结束时间=NOW() |
| 2 | 更新明细状态 | UPDATE明细表 SET 处理状态=?, 处理节点=? |

---

### 4.16 UC-17: 检查串行约束

**类**: `SerialConstraintChecker`

**内存Map结构**:
- key: 类别代号+控制代号
- value: ProcessingInfo { 节点ID列表, 是否有S类型子指令 }

**核心流程**:

| 步骤 | 条件 | 检查逻辑 |
|------|------|----------|
| 1 | S类型子指令 | 检查同节点是否有S类型指令在执行 |
| 2 | 串行标识=Y | 检查是否有其他节点在执行 |
| 3 | 串行标识=N | 检查该节点自身是否已有执行 |
| 4 | 返回 | true=允许执行, false=跳过 |

---

### 4.17 UC-18: 生成下发目录

**类**: `DirectoryGenerator`

**核心流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 解析目录路径 | 含占位符替换 |
| 2 | 检查总标志 | 如果存在且不允许覆盖则抛异常 |
| 3 | 创建目录 | FTP mkdirs创建多层目录 |

---

### 4.18 UC-19: 写入结果表

**类**: `ResultWriter`

**核心流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 插入结果记录 | 写入指令ID、类别代号、控制代号等 |
| 2 | 记录传输信息 | FTP名称、文件路径、数据库信息 |
| 3 | 记录处理结果 | 状态、耗时、记录数、文件大小 |

---

### 4.19 UC-21: 监控子指令完成

**类**: `ChildCommandMonitor`

**核心流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 获取子指令数 | 从主指令额外信息解析 |
| 2 | 轮询检查状态 | 查询所有S类型子指令状态 |
| 3 | 判断完成 | 全部完成(P状态消失)则结束循环 |
| 4 | 等待重试 | 每5秒检查一次 |
| 5 | 汇总结果 | 根据是否有失败标记最终状态 |
| 6 | 生成总标志 | 成功后生成总标志文件 |

---

### 4.20 UC-22: 应用启动

**类**: `StartupService`

**核心流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 初始化连接池 | 初始化数据库和FTP连接池 |
| 2 | 加载配置 | UC-03加载传输配置到内存 |
| 3 | 异常恢复 | 将本节点处理中的作业标记为跳过 |
| 4 | 启动轮询 | 启动定时轮询线程 |

---

### 4.21 UC-23: 应用退出

**类**: `ShutdownService`

**核心流程**:

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 停止轮询 | 停止定时轮询 |
| 2 | 等待任务完成 | 等待30秒内有任务完成，否则强制中断 |
| 3 | 关闭连接池 | 关闭FTP和数据库连接池 |

---

## 5. 数据库访问设计

### 5.1 JdbcTemplate封装

**类**: `JdbcTemplateWrapper`

JdbcTemplateWrapper是对Spring JdbcTemplate的轻量级封装，提供数据库操作的基础能力。

**核心方法**:

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| query | sql, rowMapper, params | List<T> | 查询返回List |
| queryForList | sql, params | List<Map<String, Object>> | 查询返回Map列表 |
| queryForMap | sql, params | Map<String, Object> | 查询返回单条 |
| queryForObject | sql, requiredType, params | T | 查询单个值 |
| update | sql, params | int | 更新INSERT/UPDATE/DELETE |
| batchUpdate | sql, paramsList | int[] | 批量更新 |
| execute | sql | void | 执行DDL |
| rollback | - | void | 事务回滚 |

**DbPool多数据源管理**:

| 方法 | 说明 |
|------|------|
| getConnection(dbName) | 获取指定数据库的JdbcTemplateWrapper |
| initDataSource(config) | 初始化数据源并加入连接池 |

---

### 5.2 动态SQL构建器

#### 5.2.1 SqlBuilder核心接口

**类**: `SqlBuilder`

提供动态SQL构建能力，支持INSERT、SELECT、UPDATE、DELETE的动态拼装。

**核心方法**:

| 方法 | 说明 |
|------|------|
| buildInsert | 构建INSERT SQL，支持额外字段 |
| buildBatchInsert | 构建批量INSERT SQL |
| buildSelect | 构建SELECT SQL，支持WHERE/ORDER/LIMIT/OFFSET |
| buildDelete | 构建DELETE SQL |
| buildCount | 构建COUNT SQL |

**SQL模板说明**:

| SQL类型 | 模板格式 |
|--------|----------|
| INSERT | `INSERT INTO {表} ({字段}) VALUES ({?})` |
| 批量INSERT | `INSERT INTO {表} ({字段}) VALUES (?,?), (?,?), ...` |
| SELECT | `SELECT {字段} FROM {表} WHERE {条件} ORDER BY {排序} LIMIT {条数} OFFSET {偏移}` |
| DELETE | `DELETE FROM {表} WHERE {条件}` |
| COUNT | `SELECT COUNT(*) FROM {表} WHERE {条件}` |

#### 5.2.2 字段过滤器

**类**: `FieldFilter`

根据配置过滤字段，实现INSERT动态字段拼装。

**核心方法**:

| 方法 | 说明 |
|------|------|
| filterIgnoreFields | 过滤忽略字段配置中的字段 |
| filterDetailFields | 过滤明细表指定字段（避免重复插入） |
| mergeWithDetailFields | 合并主字段和明细表额外字段 |

---

### 5.3 元数据服务

**类**: `MetadataService`

获取数据库表结构信息，支持字段列表查询和分区表探测。

**核心方法**:

| 方法 | 说明 |
|------|------|
| getTableFields | 获取表的所有字段名列表 |
| isPartitionTable | 判断是否为分区表 |
| getPartitionNames | 获取分区表的所有分区名 |
| getTableColumnMeta | 获取表字段元数据（类型、大小等） |

---

### 5.4 动态SQL使用示例

#### 5.4.1 上传场景-动态INSERT

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 解析文件 | 获取文件数据 |
| 2 | 获取表字段 | MetadataService获取字段列表 |
| 3 | 过滤字段 | 过滤忽略字段 |
| 4 | 构建SQL | SqlBuilder.buildInsert |
| 5 | 批次插入 | 逐条插入 |

#### 5.4.2 上传场景-带明细表指定字段

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 解析文件 | 获取文件数据 |
| 2 | 获取并过滤字段 | 过滤忽略字段和明细指定字段 |
| 3 | 合并额外字段 | 加上明细表提供的额外字段 |
| 4 | 构建SQL | SqlBuilder.buildInsert |
| 5 | 批次插入 | 补充额外字段值后插入 |

#### 5.4.3 下发场景-批次SELECT

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 获取下发字段 | 过滤忽略字段 |
| 2 | 构建WHERE | 根据配置构建条件 |
| 3 | 批次读取 | 分批LIMIT OFFSET读取 |
| 4 | 生成文件 | 流式写入FTP |

#### 5.4.4 下发场景-分区表遍历

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 判断分区表 | MetadataService.isPartitionTable |
| 2 | 获取分区列表 | MetadataService.getPartitionNames |
| 3 | 遍历分区 | 按分区读取并生成文件 |

---

### 5.5 关键SQL汇总

| 操作 | SQL模板 |
|------|---------|
| 竞争指令 | `UPDATE 指令表 SET 处理节点=?, 处理状态='P' WHERE 自增列=? AND 处理状态='空'` |
| 竞争分桶 | `UPDATE 明细表 SET 处理节点=?, 处理状态='P' WHERE 自增列=? AND 处理状态='空'` |
| 插入数据 | `INSERT INTO {表} ({字段}) VALUES ({?})` |
| 批量插入 | `INSERT INTO {表} ({字段}) VALUES (?,?), (?,?), ...` |
| 读取数据 | `SELECT {字段} FROM {表} WHERE {条件} LIMIT ? OFFSET ?` |
| 统计数量 | `SELECT COUNT(*) FROM {表} WHERE {条件}` |
| 清空表 | `DELETE FROM {表}` |
| 分区查询 | `SELECT * FROM {表} PARTITION ({分区名})` |
| 判断分区表 | `SELECT COUNT(*) FROM information_schema.partitions WHERE table_name=?` |
| 获取字段 | `SELECT column_name FROM information_schema.columns WHERE table_name=?` |
```

### 5.2 关键SQL汇总

| 操作 | SQL模板 |
|------|---------|
| 竞争指令 | `UPDATE 指令表 SET 处理节点=?, 处理状态='P' WHERE 自增列=? AND 处理状态='空'` |
| 竞争分桶 | `UPDATE 明细表 SET 处理节点=?, 处理状态='P' WHERE 自增列=? AND 处理状态='空'` |
| 插入数据 | `INSERT INTO {表} ({字段}) VALUES ({?})` |
| 批量插入 | `INSERT INTO {表} ({字段}) VALUES (?,?), (?,?), ...` |
| 读取数据 | `SELECT {字段} FROM {表} WHERE {条件} LIMIT ? OFFSET ?` |
| 统计数量 | `SELECT COUNT(*) FROM {表} WHERE {条件}` |
| 清空表 | `DELETE FROM {表}` |
| 分区查询 | `SELECT * FROM {表} PARTITION ({分区名})` |
| 判断分区表 | `SELECT COUNT(*) FROM information_schema.partitions WHERE table_name=?` |
| 获取字段 | `SELECT column_name FROM information_schema.columns WHERE table_name=?` |

---

## 6. 转换器插件设计
    }
}
```

### 5.4 关键SQL汇总

```sql
-- 释放超时任务
UPDATE 指令表 SET 处理状态='E', 指令处理结束时间=NOW()
WHERE 处理状态='P' AND 处理节点=? AND 指令处理起始时间 < NOW() - INTERVAL 1 HOUR;

-- 查询正在处理的指令（加载到内存）
SELECT 处理节点, 类别代号, 控制代号, 指令类型 FROM 指令表
WHERE 处理状态='P' AND 处理节点 IS NOT NULL;

-- 查询就绪指令
SELECT 自增列, 类别代号, 控制代号, 指令类型, 稽核数, 额外信息 FROM 指令表
WHERE 处理状态='空' AND 处理节点 IS NULL ORDER BY 自增列 ASC LIMIT 20;

-- 竞争指令执行权
UPDATE 指令表 SET 处理节点=?, 指令处理起始时间=NOW(), 处理状态='P'
WHERE 自增列=? AND 处理状态='空' AND 处理节点 IS NULL;

-- 竞争分桶明细
UPDATE 明细表 SET 处理节点=?, 处理状态='P'
WHERE 自增列=? AND 处理状态='空';

-- 插入S类型子指令
INSERT INTO 指令表 (类别代号, 控制代号, 指令类型, 额外信息, 稽核数)
VALUES (?, ?, 'S', ?, -1);

-- 更新指令状态
UPDATE 指令表 SET 处理状态=?, 指令处理结束时间=NOW() WHERE 自增列=?;

-- 写入结果表
INSERT INTO 结果表 (指令ID, 类别代号, 控制代号, FTP名称, 文件路径, 数据库信息,
    传输方向, 传输日期, 处理结果, 处理起始时间, 处理耗时ms, 数据记录数量, 文件大小, 结果说明)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

-- 查询分区信息（GaussDB）
SELECT partition_name FROM information_schema.partitions
WHERE table_name = ? AND partition_name IS NOT NULL;
```

---

## 6. 转换器插件设计

### 6.1 转换器接口

**文件**: `FileConverter.java`

**接口定义**:

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| parse | input, mapping | Iterator<List<Map<String, Object>>> | 边读文件边解析，分批次返回 |
| generate | output, data, mapping | void | 分批次生成文件 |
| getFormat | - | String | 获取格式标识 |
| getDefaultConfig | - | Map<String, String> | 获取默认配置 |

### 6.2 字段映射

**文件**: `FieldMapping.java`

**核心属性**:

| 属性 | 类型 | 说明 |
|------|------|------|
| config | TransferConfig | 传输配置 |
| tableFields | List<String> | 最终表字段列表 |
| fileFields | List<String> | 文件字段列表 |
| extraFields | Map<String, String> | 额外字段（明细表指定） |

**核心方法**:

| 方法 | 说明 |
|------|------|
| getTableFields | 获取最终表字段列表 |
| getValue | 获取记录值，优先从记录中取，其次从额外字段取 |

### 6.3 DBFConverter

**流式解析**:
- 使用DbfReader读取输入流
- 分批次返回数据，每批默认1000条
- 迭代器hasNext()时加载下一批数据

**流式生成**:
- 使用DbfWriter写入输出流
- 先设置字段映射
- 按批次写入记录

### 6.4 XMLConverter

**流式解析**:
- 使用XMLStreamReader读取输入流
- 按批次解析XML元素构建记录
- 每批最大1000条

### 6.5 CSVConverter

**流式解析**:
- 使用BufferedReader读取输入流
- 第一行为表头
- 按批次解析CSV行构建记录

### 6.6 TXTConverter

**定长模式解析**:
- 按配置的字段宽度切割行
- 支持有无表头两种模式
- 无表头时按位置命名FIELD_0, FIELD_1, ...

**分隔符模式解析**:
- 按分隔符切割行
- 第一行为表头

---

## 7. FTP连接池设计

### 7.1 连接池结构

**文件**: `FtpPool.java`

**核心方法**:

| 方法 | 说明 |
|------|------|
| getClient(ftpId) | 从连接池获取客户端，借用失败则创建新连接 |
| returnClient(ftpId, client) | 归还客户端到连接池 |
| close() | 关闭所有连接池 |

### 7.2 FTP客户端封装

**文件**: `FtpClient.java`

**核心方法**:

| 方法 | 说明 |
|------|------|
| connect | 连接FTP服务器 |
| exists(path) | 判断文件是否存在 |
| mkdirs(path) | 创建多层目录 |
| rename(from, to) | 重命名文件 |
| getInputStream(path) | 获取输入流 |
| getOutputStream(path) | 获取输出流 |
| close | 归还到连接池而非真正关闭 |

### 7.3 连接池配置

**文件**: `FtpPoolConfig.java`

```yaml
ftp:
  config:
    - id: FTP_DEFAULT
      host: 192.168.1.101
      port: 21
      username: ftp_user
      password: encrypted_password
      timeout: 30000
      pool:
        maxTotal: 10
        maxIdle: 5
        minIdle: 2
```

---

## 8. 错误处理设计

### 8.1 异常体系

**类层次**:

| 类 | 说明 |
|---|------|
| FmsyException | 基类，包含errorCode和status |
| FlagFileNotFoundException | 标志文件未到达('N') |
| AuditFailedException | 稽核失败('E') |
| ConfigNotFoundException | 配置不存在('E') |

### 8.2 全局异常处理

**文件**: `FmsyExceptionHandler`

使用AOP拦截transfer包的异常：
- 业务异常返回Result.error(status, message)
- 系统异常返回Result.error('E', "系统异常")

### 8.3 错误码定义

| 错误码 | 说明 | 处理策略 |
|--------|------|----------|
| FLAG_001 | 标志文件未到达 | 跳过（N） |
| FLAG_002 | 标志文件校验失败 | 异常（E） |
| AUDIT_001 | 前稽核失败 | 异常（E） |
| AUDIT_002 | 后稽核失败 | 回滚+异常（E） |
| CONFIG_001 | 配置不存在 | 异常（E） |
| DB_001 | 数据库连接失败 | 异常（E） |
| FTP_001 | FTP连接失败 | 异常（E） |
| CONVERT_001 | 文件解析失败 | 异常（E） |

---

## 9. 状态值定义

### 9.1 处理状态枚举

| 枚举值 | 代码 | 说明 |
|--------|------|------|
| EMPTY | 空 | 就绪 |
| PROCESSING | P | 处理中 |
| SUCCESS | Y | 成功 |
| SKIPPED | N | 跳过 |
| ERROR | E | 异常 |

### 9.2 状态流转图

```
指令表状态流转:
                    ┌──────┐
                    │ 空   │ ← 新插入
                    └──┬───┘
                       │ UC-02竞争成功
                       ▼
                    ┌──────┐
              ┌──────│ P    │ ← 处理中
              │      └──┬───┘
              │         │ 完成/失败
              ▼         ▼
         ┌──────┐  ┌──────┐
         │ N    │  │ Y/E  │
         └──┬───┘  └──┬───┘
            │         │
            ▼         ▼
         跳过      成功/失败

明细表状态流转:
                    ┌──────┐
                    │ 空   │ ← 创建分桶记录
                    └──┬───┘
                       │ UC-07竞争成功
                       ▼
                    ┌──────┐
              ┌──────│ P    │ ← 处理中
              │      └──┬───┘
              │         │ 完成/失败
              ▼         ▼
         ┌──────┐  ┌──────┐
         │ N    │  │ Y/E  │
         └──┬───┘  └──┬───┘
            │         │
            ▼         ▼
         跳过      成功/失败
```

---

## 10. 部署架构

### 10.1 单节点部署

```
┌─────────────────────────────────────────────────────────┐
│                    FMSY Application                     │
│  ┌─────────────────────────────────────────────────┐   │
│  │ PollingService (轮询线程)                         │   │
│  │   ↓                                             │   │
│  │ TaskExecutor (线程池)                            │   │
│  │   ├── TransferService-1                         │   │
│  │   ├── TransferService-2                         │   │
│  │   └── ... (可配置并发数)                         │   │
│  └─────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────┤
│                   外部依赖                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐  │
│  │  GaussDB    │  │  FTP Server │  │  本地文件系统    │  │
│  │  (主库)     │  │             │  │  (日志目录)     │  │
│  └─────────────┘  └─────────────┘  └─────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 10.2 多节点部署

```
┌──────────────────────────────────────────────────────────────┐
│                      负载均衡器                              │
└───────────────────────────┬────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌───────────────┐   ┌───────────────┐   ┌───────────────┐
│   FMSY Node A │   │   FMSY Node B │   │   FMSY Node C │
│  (hostname-A) │   │  (hostname-B) │   │  (hostname-C) │
└───────┬───────┘   └───────┬───────┘   └───────┬───────┘
        │                   │                   │
        ▼                   ▼                   ▼
┌──────────────────────────────────────────────────────────────┐
│                        GaussDB                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  指令表/明细表/结果表/传输配置表                           │  │
│  └──────────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│                        FTP Server                              │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  /data/upload/   ← 上传文件目录                          │  │
│  │  /data/export/   ← 下发文件目录                          │  │
│  └──────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### 10.3 配置文件

**application.yml**:
```yaml
spring:
  application:
    name: fmsy

database:
  config:
    - id: DB_DEFAULT
      host: 192.168.1.100
      port: 5432
      database: fmsy
      username: fmsy_user
      password: ${DB_PASSWORD}

ftp:
  config:
    - id: FTP_DEFAULT
      host: 192.168.1.101
      port: 21
      username: ftp_user
      password: ${FTP_PASSWORD}
      timeout: 30000
      pool:
        maxTotal: 10
        maxIdle: 5
        minIdle: 2

app:
  node:
    id: ${HOSTNAME}  # 使用主机名作为节点ID
  polling:
    interval: 10
    batchSize: 20
    enabled: true
  thread:
    corePoolSize: 10
    maxPoolSize: 20
  log:
    path: /var/log/fmsy
    level: INFO
  retry:
    maxAttempts: 3
    backoffMs: 1000
  upload:
    threadCount: 3
  download:
    bucketBatchSize: 3
  timeout:
    taskTimeoutHours: 1
```

---

## 11. 性能参数建议

### 11.1 默认配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 轮询间隔 | 10秒 | 5-300秒可配置 |
| 每批次获取指令数 | 20 | 高吞吐场景建议50-100 |
| 单节点并发处理线程数 | 10 | 根据CPU核心数调整 |
| 单节点多文件并发数 | 3 | 避免事务爆炸 |
| 分桶明细每批获取数 | 3 | - |
| 任务执行超时时间 | 1小时 | 超时自动释放 |
| FTP连接池最大连接数 | 10 | - |
| FTP连接超时 | 30秒 | - |

### 11.2 高吞吐配置

| 场景 | 轮询间隔 | 每批次获取 | 单节点并发 |
|------|----------|-----------|------------|
| 默认 | 10秒 | 20 | 10 |
| 高吞吐 | 5秒 | 50-100 | 10-20 |
| 超高吞吐 | 5秒 | 100 | 20-30 |

### 11.3 性能估算

**单节点**:
- 每批次20指令 × 6批次/分钟 × 10并发 = 1200指令/分钟
- 实际吞吐取决于文件大小和网络延迟

**线性扩展**:
- 3节点部署 ≈ 3倍吞吐
- 5节点部署 ≈ 5倍吞吐

---

## 12. 附录

### 12.1 包结构一览

```
com.fmsy
├── FmsyApplication.java
├── config
│   ├── AppConfig.java
│   ├── DataSourceConfig.java
│   ├── FtpPoolConfig.java
│   └── AsyncConfig.java
├── polling
│   ├── PollingService.java
│   ├── CommandCompetition.java
│   └── SerialConstraintChecker.java
├── transfer
│   ├── TransferService.java
│   ├── UploadExecutor.java
│   ├── DownloadExecutor.java
│   ├── BucketDistributor.java
│   └── placeholder
│       └── PlaceholderResolver.java
├── audit
│   ├── PreAuditService.java
│   └── PostAuditService.java
├── fileops
│   ├── FlagFileChecker.java
│   ├── FlagFileProcessor.java
│   ├── ErrorFileHandler.java
│   ├── DirectoryGenerator.java
│   └── TotalFlagGenerator.java
├── detail
│   ├── DetailPollingService.java
│   └── DetailUpdater.java
├── result
│   └── ResultWriter.java
├── child
│   └── ChildCommandMonitor.java
├── lifecycle
│   ├── StartupService.java
│   └── ShutdownService.java
├── converter
│   ├── FileConverter.java
│   ├── FieldMapping.java
│   ├── dbfs
│   │   └── DbfConverter.java
│   ├── xml
│   │   └── XmlConverter.java
│   ├── csv
│   │   └── CsvConverter.java
│   └── txt
│       └── TxtConverter.java
├── ftp
│   ├── FtpPool.java
│   ├── FtpClient.java
│   └── FtpOperationTemplate.java
├── db
│   ├── JdbcTemplateWrapper.java
│   ├── SqlBuilder.java
│   └── PartitionHelper.java
├── model
│   ├── Command.java
│   ├── Detail.java
│   ├── Result.java
│   └── TransferConfig.java
├── enums
│   ├── TransferScenario.java
│   ├── CommandType.java
│   ├── ProcessStatus.java
│   └── EmptyDataHandling.java
└── util
    ├── DateUtils.java
    ├── LogUtils.java
    └── ThreadLocalUtils.java
```

### 12.2 组件接口定义

**FileConverter接口**:

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| parse | input, mapping | Iterator<List<Map<String, Object>>> | 解析文件 |
| generate | output, data, mapping | void | 生成文件 |
| getFormat | - | String | 格式标识 |
| getDefaultConfig | - | Map<String, String> | 默认配置 |

**TransferConfig关键方法**:

| 方法 | 说明 |
|------|------|
| getCategoryCode | 类别代号 |
| getControlCode | 控制代号 |
| getScenario | 传输场景 |
| getFilePath | 文件路径 |
| getTableName | 数据库表名 |
| getParserType | 解析器类型 |
| getPreOperations | 前置文件操作 |
| getPostOperations | 后置文件操作 |
| getIgnoreFields | 忽略字段配置 |
| getSplitFields | 拆分字段配置 |
| getFtpName | FTP名称 |
| getDbName | 数据库名称 |
| getEmptyDataHandling | 空数据处理 |