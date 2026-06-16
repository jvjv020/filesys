package com.fmsy.model;

import com.fmsy.util.ColumnNames;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 结果实体 — 同时承担传输结果的"数据 + 生命周期"职责。
 *
 * <p>字段分两类:
 * <ul>
 *   <li><b>持久化字段(14 列)</b>:对应结果表,由 Orchestrator 调 ResultRepository 写入</li>
 *   <li><b>路由/瞬态字段</b>:{@code dbName}(选 JdbcTemplate)/ {@code startTimeMs}(耗时基准)/
 *       {@code suppressStatusUpdate}(MultiNode 抑制指令表更新)— 均不入表</li>
 * </ul>
 *
 * <p>典型用法(Orchestrator):
 * <pre>
 * Result result = new Result();
 * result.setTransferDirection(Result.DIRECTION_UPLOAD);
 * result.markStart();
 * try {
 *     singleHandler.handle(command, config, result);
 * } catch (Exception e) {
 *     result.failWith(e);
 * } finally {
 *     // Orchestrator 收尾:从 result 反查 elapsed,组装 Command/Config 派生字段,写库
 *     result.markEnd(command, config);
 *     if (!result.isSuppressStatusUpdate()) {
 *         commandRepository.updateStatus(command.getId(), result.getResult());
 *     }
 *     resultWriter.write(result);
 * }
 * </pre>
 *
 * <p>对需要一次性填齐 14 列持久化字段的场景(典型如 polling/DetailPollingService 直接写结果行),
 * 可用 {@link #builder()} 走 fluent API 构造:
 * <pre>
 * Result result = Result.builder()
 *     .commandId(subCommand.getId())
 *     .categoryCode(subCommand.getCategoryCode())
 *     ...
 *     .build();
 * </pre>
 *
 * <p>Handler 在分支处调用(运行时修改状态,不是构造期):
 * <ul>
 *   <li>{@link #setOutcome(int, String, String)} — 直接设记录数/状态/描述</li>
 *   <li>{@link #markChildrenCreated(int)} / {@link #markChildrenFailed(String)} — MultiNode 语义</li>
 *   <li>{@link #failWith(Exception)} — Orchestrator 捕获到异常时</li>
 * </ul>
 *
 * <p>本类不依赖 repository / transfer 包(纯 model + util);
 * 跨层调用由 Orchestrator 完成。
 */
@Slf4j
@Data
public class Result {

    public static final String DIRECTION_UPLOAD = "UPLOAD";
    public static final String DIRECTION_DOWNLOAD = "DOWNLOAD";

    // ==================== 持久化字段(结果表 14 列) ====================
    private Long commandId;
    private String categoryCode;
    private String controlCode;
    private String ftpName;
    private String filePath;
    private String dbInfo;
    private LocalDate transmissionDate;
    private String result;
    private LocalDateTime startTime;
    private Long durationMs;
    private Integer recordCount;
    private Long fileSize;
    private String description;
    private String transferDirection;

    // ==================== 瞬态字段(不入表) ====================

    /**
     * 写入目标数据源路由标识 — ResultRepository 用来选 JdbcTemplate。
     * null 或空时回退到 DEFAULT_DB(见 ResultRepository.getJdbc)。
     */
    private String dbName;

    /** Orchestrator 在 markStart() 时记录,markEnd() 时算 durationMs */
    @Setter(AccessLevel.NONE)
    private transient long startTimeMs;

    /**
     * MultiNode Download 成功时设为 true:Orchestrator 跳过更新指令表,等 monitor 收尾。
     * 非结果表字段,仅 Orchestrator 内部使用。
     */
    @Setter(AccessLevel.NONE)
    private transient boolean suppressStatusUpdate;

    /**
     * MultiNode Download 子命令创建数,Orchestrator 据此启停 ChildCommandMonitor。
     * 与 {@link #needsChildMonitor} 配合使用。
     */
    @Setter(AccessLevel.NONE)
    private transient int expectedChildren;

    /**
     * MultiNode Download 成功创建子命令时设为 true:Orchestrator 走完 finalize 后
     * 自动调 {@code ChildCommandMonitor.start(...)} 启动后台监控线程。
     * 非结果表字段,仅 Orchestrator 内部使用。
     */
    @Setter(AccessLevel.NONE)
    private transient boolean needsChildMonitor;

    // ==================== 生命周期方法(本类自洽,不依赖外部包) ====================

    /**
     * 记录处理起始时间。Orchestrator 在 try 块之前调一次。
     */
    public void markStart() {
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * 早返分支直接设记录数/状态/描述(description null-safe)。
     */
    public void setOutcome(int records, String status, String description) {
        this.recordCount = records;
        this.result = status;
        this.description = description != null ? description : "";
    }

    /**
     * MultiNode Download 成功:子命令已创建,主命令保持 PROCESSING 等待 monitor 收尾。
     * Orchestrator 据此跳过 updateStatus,并在 finalize 之后调
     * {@code ChildCommandMonitor.start(commandId, expectedChildren)} 启动后台监控。
     */
    public void markChildrenCreated(int expectedChildren) {
        this.result = ColumnNames.STATUS_PROCESSING;
        this.suppressStatusUpdate = true;
        this.expectedChildren = expectedChildren;
        this.needsChildMonitor = true;
    }

    /**
     * MultiNode Download 失败:无 splitFields / 无桶 / 创建失败等,主命令置 ERROR。
     */
    public void markChildrenFailed(String reason) {
        this.result = ColumnNames.STATUS_ERROR;
        this.description = reason != null ? reason : "";
    }

    /**
     * Orchestrator 捕获到异常时调用。description 来自异常消息。
     */
    public void failWith(Exception e) {
        this.result = ColumnNames.STATUS_ERROR;
        this.description = e.getMessage() != null ? e.getMessage() : "";
    }

    /**
     * Orchestrator 收尾时调用:防御性置 ERROR + 组装 Command/Config 派生字段 + 算 durationMs。
     * 不做写库(由 Orchestrator 调 CommandRepository.updateStatus + ResultRepository.insert)。
     */
    public void markEnd(Command command, TransferConfig config) {
        if (result == null) {
            log.warn("Result.markEnd() called with null status for command {} (direction: {}), " +
                    "defaulting to ERROR", command.getId(), transferDirection);
            this.result = ColumnNames.STATUS_ERROR;
        }
        this.commandId = command.getId();
        this.categoryCode = command.getCategoryCode();
        this.controlCode = command.getControlCode();
        this.ftpName = config.getFtpName();
        this.filePath = config.getFilePath();
        this.dbInfo = config.getTableName();
        this.transmissionDate = LocalDate.now();
        this.startTime = command.getStartTime() != null ? command.getStartTime() : LocalDateTime.now();
        this.durationMs = System.currentTimeMillis() - startTimeMs;
        this.fileSize = 0L;
    }

    // ==================== Builder(用于一次性填齐持久化字段的场景) ====================

    /**
     * 创建 Result Builder — 适用于不经过 Orchestrator 编排、直接组装结果行的场景
     * (典型如 {@code polling/DetailPollingService} 在子命令结束时写结果行)。
     *
     * <p>对应 14 列持久化字段 + dbName 路由字段。每个 setter 返回 {@code this},支持链式调用。
     * 运行时方法(markStart / setOutcome / markEnd / failWith 等)不在 Builder 中,
     * 因为这些方法修改 Result 状态,应作用于已构建的 Result 实例上。
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Result result = new Result();

        public Builder commandId(Long commandId) { result.commandId = commandId; return this; }
        public Builder categoryCode(String categoryCode) { result.categoryCode = categoryCode; return this; }
        public Builder controlCode(String controlCode) { result.controlCode = controlCode; return this; }
        public Builder ftpName(String ftpName) { result.ftpName = ftpName; return this; }
        public Builder filePath(String filePath) { result.filePath = filePath; return this; }
        public Builder dbInfo(String dbInfo) { result.dbInfo = dbInfo; return this; }
        public Builder dbName(String dbName) { result.dbName = dbName; return this; }
        public Builder transmissionDate(LocalDate transmissionDate) { result.transmissionDate = transmissionDate; return this; }
        public Builder status(String status) { result.result = status; return this; }
        public Builder startTime(LocalDateTime startTime) { result.startTime = startTime; return this; }
        public Builder durationMs(long durationMs) { result.durationMs = durationMs; return this; }
        public Builder recordCount(Integer recordCount) { result.recordCount = recordCount; return this; }
        public Builder fileSize(Long fileSize) { result.fileSize = fileSize; return this; }
        public Builder description(String description) { result.description = description; return this; }
        public Builder transferDirection(String transferDirection) { result.transferDirection = transferDirection; return this; }

        /**
         * 调用 {@link Result#markStart()} 记录处理起始时间。
         */
        public Builder markStart() { result.markStart(); return this; }

        public Result build() {
            return result;
        }
    }
}
