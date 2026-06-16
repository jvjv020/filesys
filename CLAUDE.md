# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FMSY (File Transfer Management System) is a polling-driven, multi-node Java service that moves data files between FTP servers and GaussDB (PostgreSQL-compatible) databases. It supports DBF/XML/CSV/TXT bidirectional conversion. The authoritative requirements document is `docs/需求规格说明v1.0.md` (filename v1.0, internal version V1.5).

## Build & Test

This project uses **Gradle** with the wrapper expected at the parent directory (`D:\Project\FMSY`) — `run_test.bat` does `cd D:\Project\FMSY` then `.\gradlew.bat test`.

- **Run all tests**: `.\gradlew.bat test --no-daemon` (or `run_test.bat`)
- **Run a single test class**: `.\gradlew.bat test --tests <FullyQualifiedTestClassName> --no-daemon` — see `run_test_placeholder.bat` for an example (`--tests PlaceholderResolverTest`)
- **Run a single test method**: `.\gradlew.bat test --tests <Class>.<method> --no-daemon`
- **Build runnable jar**: `.\gradlew.bat bootJar` → produces `build/libs/fmsy.jar`

## Tech Stack

- **JDK 21**, Spring Boot **3.2.5** (web disabled — this is a standalone scheduled service)
- Persistence: **JdbcTemplate only** (no JPA, no MyBatis). Druid (`com.alibaba:druid 1.2.23`) connection pool. PostgreSQL JDBC driver (compatible with GaussDB). HikariCP is on the classpath but Druid is the active pool — do not switch.
- FTP: `commons-net 3.10.0` + `commons-pool2` for a self-built pooled client with primary/backup IP failover and optional NOOP health-checking
- Lombok for boilerplate (`@Data`, `@Slf4j`)
- SnakeYAML for external connection configs
- Logback for logging
- JUnit 5 (`useJUnitPlatform()`)

## High-Level Architecture

```
              ┌─────────────────────────────────┐
              │     FmsyApplication (main)      │
              │ @EnableScheduling, @SpringBoot  │
              └────────────────┬────────────────┘
                               │
                ┌──────────────┴──────────────┐
                │                             │
       PollingService                ConfigLoaderService
       (@Scheduled fixedDelay)       (loads 传输配置表 → memory once)
                │                             │
                ▼                             ▼
       BatchDispatcher (per cycle)  ConcurrentHashMap<cat+ctrl, TransferConfig>
       constraintCheck → compete
       → ConfigLoaderService.getConfigOrThrow (or getConfigOrDefault for tolerant callers) → submit
                │
                ▼
       TransferService.process(commandId, direction)
                │
        ┌───────┴────────┐
        ▼                ▼
  UploadOrchestrator  DownloadOrchestrator
   (FTP → DB)         (DB → FTP)
   (AbstractTransferOrchestrator.execute template)
                │
                ▼
        dispatch by scenario × commandType
                │
   ┌────────────┼────────────┐
   ▼            ▼            ▼
 Single     Multi-*      SingleNode / MultiNode
 (each is a Handler, injected with
  FtpPool + TransferSupport + DirectionSupport +
  FieldMappingBuilder + TargetTableRepository)
                │
        ┌───────┴───────┐
        ▼               ▼
   FileConverter     BucketDistributor
   (DBF/XML/CSV/TXT) (split-bucket for DOWNLOAD_*_NODE)
   streamed 1000/batch│
                      ▼
                ChildCommandMonitor
                (S 子命令汇总 + 写 TOTAL_FLAG)
                 │
        ┌────────┴────────┐
        ▼                 ▼
     FtpPool          JdbcTemplate (per DB id)
```

### Thread pool model

1. **Per-poll batch executor** — `BatchDispatcher.dispatch` creates a fresh `ExecutorService` *every polling cycle* (lazy, only if commands were dispatched). After dispatch it calls `shutdown()`; in-flight tasks drain in the background, the next cycle creates a new pool. `ShutdownService.beginTask()/endTask()` brackets every submission so graceful shutdown can wait for drain. **Do not introduce a long-lived shared executor here** — the per-cycle isolation is intentional.
2. **Per-directory sub-pool** — `MultiDirectoryUploadHandler` and `SingleNodeDownloadHandler` build their own `ExecutorService` per call (via the `batchExecutorFactory` bean from `AsyncConfig`) for parallel file / bucket processing.

### Multi-node coordination

- **Atomic competition**: `UPDATE 指令表 SET 处理节点=?, 处理状态='P' WHERE 处理状态='空' AND 处理节点 IS NULL AND id=?` — the row update count determines the winner.
- **Serial constraint** (same `类别代号+控制代号` cannot run concurrently across nodes): enforced in-memory via `processingMap` rebuilt every poll cycle from the DB. `S`-type collaborative children belonging to the *same main command on the same node* are exempt.
- **DOWNLOAD_MULTI_NODE**: the main command splits buckets into `S`-type child commands (one per bucket) inserted into the command table; any node may compete for them. `ChildCommandMonitor` on the main node waits for all children to finish, then aggregates status (Y/N/E) for the total flag file.
- **Startup recovery**: on boot, `StartupService` releases this node's stale `P` rows; `PollingService.releaseTimeoutTasks()` continues to release rows past `app.polling.taskTimeoutHours`.
- **Graceful shutdown**: `ShutdownService` stops accepting new tasks, waits for in-flight ones, then `@PreDestroy` closes `FtpPool` and `DbPool` (Druid).

### Streaming I/O contract

Both directions stream batches (~1000 rows per `CloseableIterator.next()`) — uploads parse → write DB without buffering the whole file, downloads query DB → write FTP iteratively. `CloseableIterator` chains the source `AutoCloseable` (InputStream / ResultSet) into batch iteration so the underlying resource is released as soon as the batch is consumed. **New converters and large-data code paths must preserve streaming** — do not collect to `List` mid-pipeline.

### Configuration is loaded once

`传输配置表` (transfer config table) is loaded into a `ConcurrentHashMap<类别+控制, TransferConfig>` at startup by `ConfigLoaderService`. Runtime lookups go to memory only — DB is not re-queried per command. If config is missing for a polled command, the command is marked `E` and a result row is written (see `TransferService.process` / `PollingService.poll`).

## Domain Concepts

### Transmission Scenarios (`enums/TransferScenario`)
- `UPLOAD_SINGLE` — one file matches a placeholder pattern
- `UPLOAD_MULTI` — directory match or explicit file list via detail table
- `DOWNLOAD_SINGLE` — whole table → one file
- `DOWNLOAD_SINGLE_NODE` — split-by-field, one file per bucket, single node
- `DOWNLOAD_MULTI_NODE` — split-by-field, distributed across nodes via `S` children

### Command Types (`enums/CommandType`)
| Enum | DB code | Meaning |
|------|---------|---------|
| `SERIAL` | `null` | Same `类别+控制` must serialize across nodes |
| `BATCH` | `'R'` | Uses detail-table rows for file names / bucket values |
| `COORDINATED` | `'S'` | Child command created by main for multi-node bucket processing |

**Always compare via the enum** (`CommandType.BATCH`, `CommandType.COORDINATED`) — never use the raw `"R"`/`"S"` literals.

### Status Values (`util/ColumnNames.STATUS_*`)
- `""` empty/ready · `"P"` processing · `"Y"` success · `"N"` skipped · `"E"` error
- Stored as plain `String` in `Command` / `Detail` entities (the `ProcessStatus` enum was removed in favor of the constant strings; **never use a literal `"Y"`/`"N"`/`"E"`** — use the `ColumnNames.STATUS_*` constants).

### Placeholder Syntax (`transfer/placeholder/PlaceholderResolver`)
- Date/time: `{YYYYMMDD}`, `{YYYYMMDDHHmmss}`, `{date}`, `{now}`, `{time}`
- Replacement field: `{FIELD_NAME}` (sourced from data; the field must appear in `拆分字段配置`)
- Extra info: `{EXTRA_INFO}` (from command table's `额外信息` column)
- File-derived (auto-resolved after data file path is determined):
  - `{stem}` — filename without extension
  - `{name}` — filename with extension
  - `{ext}` — extension with dot (e.g. `.csv`)
  - `{dir}` — parent directory
  - `{dn}` — last segment of parent directory
  - `{up}` — parent of parent directory

### Pre/Post File Operations (new short-keyword syntax)

**Pre-operations:**
- `READY:path` — check file exists
- `FLAG:path` — check flag exists only
- `FLAG:path;mode` — flag content vs data file computed value
- `FLAG:path;expect;mode` — literal expect vs data file computed value

Mode syntax: `[#]L|M|S[=|>|<|>=|<=|!=]` or `?`
- `#` = compute from data file (default), `@` = read from flag file
- `L` = lines, `S` = size, `M` = MD5
- `?` = existence check only

**Post-operations:**
- `FB:path;content` — feedback file (was GENERATE_FEEDBACK)
- `SUB:path;content` — sub-flag file (was GENERATE_SUB_FLAG)
- `TOTAL:path;content` — total flag file (was GENERATE_TOTAL_FLAG)
- `DEL:path` — delete (was DELETE)
- `REN:from;to` — rename (was RENAME)
- `MSG:target;body` — send message (was SEND_MESSAGE)

**Content mode codes** (for SUB/FB/TOTAL content):
- `L` = lines, `S` = size, `M` = MD5, `C` = record count
- `N` = timestamp, `D` = date, `T` = time
- `F` = filename, `X` = stem, `E` = extension, `P` = full path
- Example: `SUB:{X}.flg;L S M` → writes "1500 524288 d41d8c..."

**Path inheritance**: paths not starting with `/` automatically inherit `{dir}/` prefix (same directory as data file). `..` supported for parent directory traversal.

## Database Tables (Chinese names are part of the contract)

All table/column names live in `util/TableNames` and `util/ColumnNames` as constants — **always reference these constants in SQL strings**, never hardcode the Chinese names again.

- `指令表` (Command) — task queue; key cols: `自增列`, `类别代号`, `控制代号`, `指令类型`, `稽核数`, `额外信息`, `处理节点`, `处理状态`, `处理开始时间`, `处理结束时间`
- `明细表` (Detail) — sub-task rows for batch/bucket work
- `结果表` (Result) — outcomes (status, duration, record count, file size, description)
- `传输配置表` (TransferConfig) — declarative rules (paths, parser type, pre/post ops, split fields, empty-data handling, etc.)

## Field-mapping Mechanics (`transfer/FieldMappingBuilder`)

### Upload (`buildForUpload`, 6 steps)
1. Read target-table metadata field list (cached by `dbName|tableName`)
2. Remove ignored fields
3. If detail-table specifies "upload-specified fields", remove "specified field names"
4. The parsed file's field order = `afterExtra`
5. Align values by file order
6. Append detail-table `extraValues` (specified-field values)

### Download (`buildForDownload`, 4 steps)
1. Read source-table metadata
2. Remove ignored fields
3. Read DB records
4. Generate file in the final field order

## Repository Layout Notes

- **The Gradle wrapper lives one directory up** at `D:\Project\FMSY\` (not inside this `FMSY\` source root). All `gradlew.bat` invocations must `cd` there first — both `.bat` scripts do this.
- Source root: `src/main/java/com/fmsy/` — subpackages mirror responsibility (`polling/`, `transfer/`, `repository/`, `converter/`, `fileops/`, `ftp/`, `db/`, `audit/`, `lifecycle/`, `config/`, `model/`, `enums/`, `util/`, `exception/`)
  - `polling/` — `PollingService` (entry) + `BatchDispatcher` (per-cycle dispatch) + `SerialConstraintChecker` + `CommandProcessingTracker` (in-memory tracker) + `DetailPollingService` (S-type sub-command bucket processor)
  - `transfer/` — `TransferService` (entry) + `AbstractTransferOrchestrator` (base) + `UploadOrchestrator` / `DownloadOrchestrator` + 6 `*Handler` (one per scenario) + `TransferSupport` (cross-direction) + `UploadSupport` / `DownloadSupport` (direction-specific) + `FieldMappingBuilder` + `BucketDistributor` + `TransferUtils` + `ChildCommandMonitor` (DOWNLOAD_MULTI_NODE child aggregator, in `transfer/download/`)
  - `repository/` — 5 `*Repository` classes, one per table: `CommandRepository` / `DetailRepository` / `ResultRepository` / `TransferConfigRepository` / `TargetTableRepository`. All `SQL_*` constants live here — never write SQL in business classes.
  - `audit/` — `AuditService` + `AuditScenario` enum (`UPLOAD` / `DOWNLOAD`); replaces the old `String scenario` parameter
- Tests mirror the same package layout under `src/test/java/com/fmsy/`
- Specs/design docs live under `docs/` — `需求规格说明v1.0.md` is the source of truth for behavioral requirements; the `superpowers/specs/` and `2026-05-26-fmsy-system-design-v2.md` files are historical design records

## Shared Utilities (do not reinvent)

- `util/ColumnNames` / `util/TableNames` — **all** Chinese table/column names + 5 status code constants; every SQL string and entity reference must go through these
- `util/BooleanUtils.isYes(String)` — null-safe case-insensitive `"Y"` flag check; use instead of `!"Y".equalsIgnoreCase(...)` patterns
- `util/DateUtils` — pre-built `DateTimeFormatter` constants
- `util/LogUtils` — MDC taskId/nodeId for cross-thread log tracing
- `util/FilePathUtils` — path segment operations + traversal-safe validation
- `util/ParserConfigUtil` — minimal hand-written JSON parser for `parser_config` strings
- `util/SystemConstants` — `DEFAULT_BATCH_SIZE` (1000) / `MAX_RETRIES` / monitor intervals
- `model/Result.Builder` — fluent builder for the 14 persistent columns of the result table; required for new result construction (replaces the 14-line setter chain)
- `audit/AuditScenario` enum — pass `UPLOAD` or `DOWNLOAD` to `AuditService.preAudit/preAuditByBucket/postAudit` instead of the old `String` discriminator
- `transfer/TransferSupport` — 4 cross-direction methods (`resolveFilePath` / `preCheck` / `postProcess` / `handleEmptyData`) + `buildContext(command, splitFields, fieldValue)` for placeholder context construction; inject into any new Handler
- `transfer/placeholder/PlaceholderResolver` — single `resolve(String template, Map<String,String> context)` method; parses `$YYYYMMDD$` / `$EXTRA_INFO$` / `$FIELD_NAME$` against the context. `TransferSupport.buildContext` is the canonical way to construct the context for `Detail`/bucket scenarios
- `lifecycle/ConfigLoaderService.getConfigOrDefault(cat, ctrl)` — tolerant config lookup returning `Optional<TransferConfig>`; use for non-critical paths where missing config shouldn't fail the whole flow
- `repository/CommandRepository.markErrorWithResult(id, cat, ctrl, description)` — atomic `UPDATE 指令表 SET E + INSERT 结果表` template; use wherever "config missing / unexpected state" needs to mark a command as errored
- `config/DataSourceConfig.DbPool.resolveJdbcTemplate(dbName)` — null/empty `dbName` falls back to `ColumnNames.DEFAULT_DB`; prefer over direct `getJdbcTemplate(dbName)`
- `transfer/BucketDistributor.distinctBuckets(config)` — returns `List<String> bucket values` via `streamQuery DISTINCT splitField` + `fieldValue` assembly. Sibling methods `createBuckets` / `createChildCommands` cover bucket persistence
- `model/Command.markStartTimeIfAbsent()` — idempotent start-time setter; use instead of the `if (command.getStartTime() == null) command.setStartTime(...)` pattern

## Key Config (`application.yml`)

- `app.polling.interval` (seconds, default 10), `app.polling.batchSize` (default 20), `app.polling.taskTimeoutHours` (default 1)
- `app.upload.threadCount`, `app.download.bucketBatchSize`, `app.download.maxPollIterations` (S-child outer-poll safety cap, default 1000)
- `database.config[]` — list of DB connections (id, host, port, database, username, password) → `DataSourceConfig.DbPool` exposes per-id `JdbcTemplate` and `TransactionTemplate` (use `dbPool.getTransactionTemplate(id).execute(status -> {...})` for atomic multi-step writes that share a single DB)
- `ftp.config[]` — list of FTP servers with per-server `pool` (only `maxTotal` is read by `FtpPool`), `failover` (DNS multi-IP), `healthCheck` (`enabled` / `intervalSeconds` only; NOOP daemon) blocks
- Passwords expected via `${DB_PASSWORD}` / `${FTP_PASSWORD}` env vars
