# transfer 模块 — 传输编排与处理

## 职责
传输服务的核心编排层，包含入口服务、编排器、方向支持、Handler 实现、占位符解析。

## 关键类

### 传输入口
| 类 | 角色 |
|---|------|
| `TransferService` | 传输服务入口，按方向 + 命令类型分流 |
| `TransferOrchestrator` | 编排器，按 scenario 显式 switch 到对应 Handler（含 execute 模板） |

### 方向支持
| 类 | 角色 |
|---|------|
| `TransferSupport` | 跨方向公共方法（resolveFilePath / preCheck / postProcess / handleEmptyData） |
| `UploadSupport` | 上传特有（preAudit / postAudit / insertAndVerifyPerFileInTx / processSingleFile） |
| `DownloadSupport` | 下载特有（preAudit / postAudit / checkOverwriteAllowed / buildSplitFieldPredicates） |

### Handler（5 个，均实现 `TransferHandler`）
| Handler | 场景 |
|---------|------|
| `SingleUploadHandler` | UPLOAD_SINGLE |
| `MultiUploadHandler` | UPLOAD_MULTI（SERIAL 模式目录通配符并发 + BATCH 模式明细表顺序） |
| `SingleDownloadHandler` | DOWNLOAD_SINGLE |
| `SingleNodeDownloadHandler` | DOWNLOAD_SINGLE_NODE（并行桶处理 + 总标志文件） |
| `MultiNodeDownloadHandler` | DOWNLOAD_MULTI_NODE（创建 S 子命令 + preCheck） |

### 下载子包（`transfer/download/`）
| 类 | 角色 |
|---|------|
| `BucketDistributor` | 桶分发（distinctBuckets / createBuckets / createChildCommands / prepareBatchChildren / prepareSerialChildren） |
| `ChildBucketProcessor` | 子节点桶处理（竞争 → 查数据 → 写临时文件 → 生成 OK 哨兵） |
| `SplitFlowService` | 主节点分桶（Plan B 逐块切分，写入明细表） |
| `MergeFlowService` | 主节点合流程（轮询 Y 桶 → 检查 OK 文件 → APPE 合并 → 写标志） |
| `ParallelFileGenerator` | 按分区并行读取 DB → 临时文件 → 顺序拼接 |

### 上传子包（`transfer/upload/`）

上传包按职责分为 4 个类：

| 类 | 职责 | 角色 |
|----|------|------|
| `SingleUploadHandler` | 处理 | UPLOAD_SINGLE（SERIAL）+ UPLOAD_MULTI（BATCH 明细表单文件） |
| `MultiUploadHandler` | 处理 | UPLOAD_MULTI（SERIAL 目录通配符多文件并行） |
| `UploadSupport` | 协议 | 上传管线编排、流式插入事务、前后审计、异常文件迁移 |
| `UploadPrescanner` | 工具 | 按 flag/glob 模式从 FTP 列表中筛选有效数据文件（无状态静态方法） |

### 工具类
| 类 | 角色 |
|---|------|
| `FieldMappingBuilder` | 字段映射构建（buildForUpload 6 步 / buildForDownload 4 步） |
| `PlaceholderResolver` | 占位符解析 `{var}` 语法 |
| `TempTransferConfigFactory` | T 类型指令的 temp_config JSON → TransferConfig |
| `TransferHandler` | Handler 接口 |

## 设计要点
- **COORDINATED(S) 类型的 DOWNLOAD_MULTI_NODE** 不由 TransferOrchestrator 处理，由 TransferService 直转 ChildBucketProcessor
- Handler 不持有 FTP 连接跨越 Phase 边界，各 Phase 间释放连接
- `ParallelFileGenerator`：分区并行 → 流水线拼接，非分区表自动降级串行
- 所有流式查询通过 `DataStream` / `CloseableIterator`，禁止 `List` 收集
