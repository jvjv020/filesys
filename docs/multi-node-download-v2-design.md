# 多节点拆分下发设计 v2.0

> 对应需求：DOWNLOAD_MULTI_NODE 场景的高性能重构，支持亿级记录量拆分。

## 1. 目标

支持上亿级记录量的未分区表和分区表的**拆分下发**（多文件，按拆分字段值分组）和**单文件下发**（全表→单文件），在多节点集群中并行生成临时文件，由主节点完成合并。

## 2. 核心模型

以**主键序**为分桶依据，每桶固定 **50 万记录**（可配置 `app.download.bucketRecordSize`），将表划分为记录数量相近的记录块。每个记录块生成一个临时文件（仅数据记录，不带头尾），按序合并为目标文件。

### 2.1 拆/合分离

主节点启动两个**异步流程**：

```
拆流程 (SplitFlowService)
   ROW_NUMBER 窗口函数 → 每 bucketSize 行取一个边界 PK
   → 明细表每条记录: specName="partitionName|pkStart|pkEnd"
   → 全部插入后标记主指令 extra_info 为 SPLIT_DONE

合流程 (MergeFlowService)  
   轮询明细表 Y 状态的桶 → 按 fieldValue 分组
   → 目标文件不存在 → 预计算 COUNT → 写文件头
   → APPE 临时文件 → 删临时文件 → 状态 Y→M
   → 检测 E 桶 → 终止; 检测无空/P + SPLIT_DONE → 写文件尾 + SUB + TOTAL
```

两流程互不阻塞，合流程在分桶未全部入库时持续等待。

## 3. 场景说明

### 3.1 拆分下发（配置了拆分字段）

```
目标文件 = 配置的 filePath 模板中拆分字段占位符替换后
          (eg. /data/order_{REGION}.csv → /data/order_EAST.csv)
每文件的分桶: 按拆分字段值筛选后，按主键范围内切成桶
             (WHERE REGION='EAST' AND pk >= ? AND pk < ?)
合流程: 按 fieldValue 分组合并，不同 fieldValue 可并行(多线程)
```

### 3.2 单文件下发（无拆分字段）

```
目标文件 = 配置的 filePath（单文件）
分桶: 全表按主键范围内切分，不按拆分字段筛选
合流程: 全部桶串行 APPE 到同一个目标文件
```

## 4. 分桶算法

### 4.1 边界查询

使用 ROW_NUMBER 窗口函数一次查询获取所有桶边界：

```sql
WITH numbered AS (
    SELECT pk1, pk2,
           ROW_NUMBER() OVER (ORDER BY pk1, pk2) AS rn
    FROM table_name
    [WHERE splitField = ?]
)
SELECT pk1, pk2, rn FROM numbered
WHERE rn = 1 OR (rn - 1) % bucketSize = 0
ORDER BY rn
```

返回 PK 值在 `rn = 1, bucketSize+1, 2*bucketSize+1, ...` 的行，作为桶边界。

### 4.2 桶范围

```
桶 0: PK >= boundary[0] AND PK < boundary[1]
桶 1: PK >= boundary[1] AND PK < boundary[2]
...
桶 N-2: PK >= boundary[N-2] AND PK < boundary[N-1]
桶 N-1: PK >= boundary[N-1]              (LAST 标记)
```

specName 编码格式: `partitionName|pkStartCommaSep|pkEndCommaSep`

- 分区表: `part_202401|1000|2000`
- 非分区表: `tableName|A0001|B0001`
- LAST 桶: `tableName|Z9999|LAST`（无上界，查至表尾）

### 4.3 主键约束

- **必须有主键**：不存在主键则直接报错
- **复合主键**：GaussDB 支持元组比较语法 `(f1, f2) >= (?, ?)`，按主键列序 ORDER BY
- **主键类型**：数值类型（BIGINT/INTEGER/SERIAL）效率最优；字符串/UUID 也支持

## 5. 明细表字段约定

| 列名 | 现有列 | 新用途 |
|------|--------|--------|
| 指定文件名 (`FILE_NAME`) | 旧: 上传场景的文件名 | 新: **specName** = `partitionName\|pkStart\|pkEnd` |
| 指定字段名称 (`FIELD_NAME`) | 拆分字段名 | 拆分字段名（单文件为空） |
| 指定字段取值 (`FIELD_VALUE`) | 拆分字段值 | 拆分字段值（单文件为空） |
| 处理状态 (`PROCESS_STATUS`) | 空/P/Y/N/E | 新增 **M**(已合并) |
| 稽核数 (`AUDIT_COUNT`) | 预期记录数 | **废弃不填** |

状态流转：

```
空 → P (子节点竞争到) → Y (临时文件就绪) → M (已合并)
空 → N (E检测后跳过)
P → E (子节点处理失败)
Y → E (子节点异常写入)
```

## 5. 文件命名与目录

### 5.1 临时文件

```
{target_dir}/temp/{detailId}.tmp
```

- detailId = 明细表自增主键，全局唯一
- 子节点写入完成后状态→Y
- 合流程 APPE 后删除 .tmp 文件

### 5.2 目标文件

由配置的 `filePath` + 占位符替换确定：

```
拆分下发: {dir}/{splitValue}_{stem}.csv  (fieldValue 替换占位符)
单文件下发: {dir}/{stem}.csv  (无替换)
```

## 6. 主节点流程

### 6.1 入口（MultiNodeDownloadHandler）

1. 解析文件路径（resolveFilePath）
2. 创建 N 个 S 型子指令（N = `app.download.clusterNodeCount`）
3. 调用 `result.markMultiNodeStarted()` 抑制 Orchestrator 终态落库
4. 异步启动 SplitFlowService
5. 异步启动 MergeFlowService
6. 返回（主指令保持 P 状态，由合流程最终更新终态）

### 6.2 拆流程（SplitFlowService）

1. `partitionHelper.getPrimaryKeyColumns()` 获取主键列
2. 无主键 → 报错
3. 拆分下发 → `querySmallResult(DISTINCT splitField)` 获取所有拆分字段值
4. 逐分区（分区表）或整表：
   - 逐拆分字段值（拆分下发）或直接（单文件）
   - `queryPkBoundaries()` → 一次 SQL 获取所有桶边界
   - 构建 specName 列表
   - `detailRepository.createBuckets()` 批量插入
5. 全部插入后 `commandRepository.markSplitDone()`

### 6.3 合流程（MergeFlowService）

```
单文件下发:
  预计算 SELECT COUNT(*) → 写文件头
  循环: 查 Y 桶 → APPE 临时文件 → 删 .tmp → Y→M
  完成: 写文件尾 → SUB → TOTAL → 主指令 Y

拆分下发:
  按 fieldValue 分组 → 每组独立线程
  每组: 预计算 COUNT 当拆分字段值 → 写文件头
  逐桶 APPE → 删 .tmp → Y→M
  该组无剩余 Y → 写文件尾 + SUB
  全部分组完成 → TOTAL → 主指令 Y

异常时:
  检测到 E 桶 → 剩余空桶批量 N → 主指令 E
```

## 7. 子节点流程（ChildBucketProcessor）

### 7.1 入口

子节点接到 S 子指令（COORDINATED + DOWNLOAD），由 TransferService 直转。

### 7.2 循环处理

```
竞争: findReadyBuckets(mainId, 1) → competeBucket(id, nodeId)
  ↓ (成功)
解析: specName.split("|", 3) → (tableOrPartition, pkStartRaw, pkEndRaw)
  ↓
查询: streamByPkRange(db, table, pkCols, pkStart, pkEnd)
  ↓
写入: converter.writeDataRecords(os, data, mapping) 仅数据记录
  ↓
上传: FTP {tempDir}/{detailId}.tmp
  ↓
完成: updateStatus(id, Y, nodeId)
  ↓
检查退出: isSplitDone(mainId) && countByStatus(mainId, 空)=0
  ↓ (否) → 继续循环
```

### 7.3 退出条件

- 明细表无状态=空的桶
- 主指令 extra_info 已包含 SPLIT_DONE 标记

### 7.4 不参与

- **不写文件头/文件尾**——由合流程统一处理
- **不执行前/后稽核**——由主节点的合流程完成
- **不生成 SUB/TOTAL 标志**——由合流程处理

## 8. 协调机制

| 时机 | 主节点拆流程 | 主节点合流程 | 子节点 |
|------|-------------|-------------|--------|
| 指令创建后 | 启动异步拆分 | 启动异步轮询 | 启动子指令处理 |
| 分桶未完成时 | 逐个批量插入 | 等待（有 Y 就先合并） | 竞争空桶 |
| 分桶完成时 | 标记 SPLIT_DONE | 检测到 SPLIT_DONE | 检测到后准备退出 |
| 全部 Y + SPLIT_DONE | — | 写文件尾+SUB+TOTAL→主指令Y | — |
| 出现 E | — | 空桶→N→主指令E | 继续（容错） |

合流程关闭条件（两个均满足）：
1. 明细表无状态=空 且 无状态=P 的桶
2. 主指令 extra_info 包含 `SPLIT_DONE`

## 9. 配置项

`application.yml` 新增/变更：

```yaml
app:
  download:
    bucketRecordSize: 500000       # 每分桶记录数(默认50万)
    clusterNodeCount: 3            # 集群节点数(用于子指令创建)
```

相关常量（`SystemConstants`）：

| 常量 | 值 | 说明 |
|------|-----|------|
| `BUCKET_RECORD_SIZE` | 500000 | 默认桶大小 |
| `MERGE_POLL_INTERVAL_MS` | 3000 | 合流程轮询间隔(ms) |
| `TEMP_DIR_NAME` | "temp" | 临时文件目录名 |
| `SPLIT_DONE_FLAG` | "SPLIT_DONE" | 拆分完成标记 |

## 10. 边界情况

### 10.1 空表

`queryPkBoundaries` 返回空 → 拆流程创建 0 个桶 → 合流程立即检测到 SPLIT_DONE + 无桶 → 目标文件仅写 header + footer（空文件）

### 10.2 单行表

1 个桶 → 子节点处理 → 合流程 APPE → 写头尾 → 完成

### 10.3 E 桶（部分失败）

子节点写入异常→明细 E→合流程检测到→批量将剩余空桶置 N→终止合并→主指令 E

### 10.4 无主键表

`PartitionHelper.getPrimaryKeyColumns` 返回空 → SplitFlowService 抛异常 → 拆流程失败（日志记录，主指令超时后由 `releaseTimeoutTasks` 兜底）

### 10.5 主节点重启

拆/合流程在 CompletableFuture 中运行，主节点重启后异步任务丢失：
- 主指令保持 P → 超时后 `releaseTimeoutTasks` 释放为 E
- 子节点现存桶处理完毕后无法获取新桶 → 子指令正常退出
- 需人工介入重新下发

## 11. 依赖关系

```
MultiNodeDownloadHandler
  ├── CommandRepository.batchCreateChildCommands  (创建 S 子指令)
  ├── TransferSupport.resolveFilePath             (解析路径)
  ├── SplitFlowService.startSplitAsync            (异步)
  └── MergeFlowService.startMergeAsync            (异步)

SplitFlowService
  ├── PartitionHelper.getPrimaryKeyColumns        (主键列)
  ├── PartitionHelper.isPartitioned / getPartitions (分区)
  ├── TargetTableRepository.queryPkBoundaries     (桶边界 SQL)
  ├── TargetTableRepository.querySmallResult      (DISTINCT 拆分值)
  ├── DetailRepository.createBuckets              (插入明细)
  └── CommandRepository.markSplitDone             (标记完成)

MergeFlowService
  ├── DetailRepository.findBucketsByStatus         (查 Y/E 桶)
  ├── DetailRepository.updateStatus                (Y→M)
  ├── DetailRepository.batchUpdateStatus           (空→N)
  ├── DetailRepository.countByStatus               (计数)
  ├── CommandRepository.isSplitDone / updateStatus (标记检测/终态)
  ├── FieldMappingBuilder.buildForDownload         (字段映射)
  ├── ConverterFactory.get + writeHeader/writeFooter (头尾)
  ├── FtpPool 含 client.append / exists / deleteFile (FTP)
  └── TransferSupport.postProcess                  (SUB/TOTAL 标志)

ChildBucketProcessor
  ├── DetailRepository.findReadyBuckets / competeBucket (竞争)
  ├── DetailRepository.updateStatus                 (Y/E)
  ├── CommandRepository.isSplitDone                 (退出检测)
  ├── PartitionHelper.getPrimaryKeyColumns          (PK 列)
  ├── TargetTableRepository.streamByPkRange         (PK 范围查询)
  ├── FieldMappingBuilder.buildForDownload          (字段映射)
  ├── ConverterFactory.get + writeDataRecords       (写数据)
  └── FtpPool 含 client.getOutputStream / mkdirs   (写临时文件)
```

## 12. 路径继承

临时文件的 temp 目录自动继承自目标文件父目录。例如：

```
目标文件: /ftpdata/orders/order_{REGION}.csv
拆分值: EAST
目标路径: /ftpdata/orders/order_EAST.csv
临时目录: /ftpdata/orders/temp/
临时文件: /ftpdata/orders/temp/42.tmp
```
