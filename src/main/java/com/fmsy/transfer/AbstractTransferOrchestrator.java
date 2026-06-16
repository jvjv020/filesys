package com.fmsy.transfer;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.ResultRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * 传输编排器公共模板 — 上传 / 下载 Orchestrator 共享的 execute 骨架与单点 finalize。
 *
 * <p>模板方法包含:
 * <ul>
 *   <li>try { dispatch } catch → result.failWith(e); finally → finalize</li>
 *   <li>finalize:从 result 反查 elapsed,组装 Command/Config 派生字段,
 *       写指令表(非 MultiNode 抑制)→ 写结果表</li>
 *   <li>newResult:setTransferDirection + markStart</li>
 *   <li>MultiNode 后置:finalize 之后若 result 标记 needsChildMonitor,启动 ChildCommandMonitor 后台线程</li>
 * </ul>
 *
 * <p>子类只需注入自己的 3 个 Handler 并实现 dispatch(...),无需关心异常兜底、落库、monitor 启停。
 * direction 由子类构造期传入,决定 Result.transferDirection。
 */
@Slf4j
public abstract class AbstractTransferOrchestrator {

    private final CommandRepository commandRepository;
    private final ResultRepository resultRepository;
    private final ChildCommandMonitor childCommandMonitor;
    private final DataSourceConfig.DbPool dbPool;
    private final String direction;

    protected AbstractTransferOrchestrator(CommandRepository commandRepository,
                                           ResultRepository resultRepository,
                                           ChildCommandMonitor childCommandMonitor,
                                           DataSourceConfig.DbPool dbPool,
                                           String direction) {
        this.commandRepository = commandRepository;
        this.resultRepository = resultRepository;
        this.childCommandMonitor = childCommandMonitor;
        this.dbPool = dbPool;
        this.direction = direction;
    }

    public final void execute(Command command, TransferConfig config) {
        log.info("Executing {} for command: {}", direction, command.getId());
        Result result = newResult();
        try {
            dispatch(command, config, result);
        } catch (Exception e) {
            log.error("{} execution failed: {}", direction, e.getMessage(), e);
            result.failWith(e);
        } finally {
            finalize(command, config, result);
            startChildMonitorIfNeeded(command, result);
        }
    }

    /**
     * 按 scenario + commandType 派发到对应 Handler。异常上抛,留给外层 execute 统一处理。
     */
    protected abstract void dispatch(Command command, TransferConfig config, Result result) throws Exception;

    /**
     * 收尾:组装 Command/Config 派生字段 → 写指令表(非 MultiNode 抑制) → 写结果表。
     * <p>UPDATE 指令表 + INSERT 结果表 包在事务中:避免"指令已置终态但结果表未写"
     * 的状态(下一个超时周期才兜底为 ERROR,期间业务可见性偏差)。
     */
    private void finalize(Command command, TransferConfig config, Result result) {
        result.markEnd(command, config);
        dbPool.getTransactionTemplate(config.getDbName()).execute(status -> {
            if (!result.isSuppressStatusUpdate()) {
                commandRepository.updateStatus(command.getId(), result.getResult());
            }
            resultRepository.insert(result);
            return null;
        });
    }

    /**
     * MultiNode Download 子命令创建后启动后台监控线程。
     * 由 Handler 通过 {@link Result#markChildrenCreated(int)} 触发,本方法仅在 finally 中检测 flag。
     */
    private void startChildMonitorIfNeeded(Command command, Result result) {
        if (!result.isNeedsChildMonitor()) {
            return;
        }
        try {
            childCommandMonitor.start(command.getId(), result.getExpectedChildren());
        } catch (Exception e) {
            log.error("Failed to start child command monitor for cmd {}: {}",
                    command.getId(), e.getMessage(), e);
        }
    }

    private Result newResult() {
        return Result.builder()
                .transferDirection(direction)
                .markStart()
                .build();
    }
}
