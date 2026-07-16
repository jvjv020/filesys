package com.fmsy.transfer;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.enums.TransferScenario;
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
 * </ul>
 *
 * <p>子类只需注入自己的 Handler 并实现 dispatch(...),无需关心异常兜底、落库等。
 * direction 由子类构造期传入,决定 Result.transferDirection。
 *
 * <p>DOWNLOAD_MULTI_NODE 场景:MultiNodeDownloadHandler 通过 result.markChildrenCreated()
 * 抑制指令表终态落库,由异步的 MergeFlowService 在合并完成后统一更新终态。
 */
@Slf4j
public abstract class AbstractTransferOrchestrator {

    private final CommandRepository commandRepository;
    private final ResultRepository resultRepository;
    private final DataSourceConfig.DbPool dbPool;

    protected AbstractTransferOrchestrator(CommandRepository commandRepository,
                                           ResultRepository resultRepository,
                                           DataSourceConfig.DbPool dbPool) {
        this.commandRepository = commandRepository;
        this.resultRepository = resultRepository;
        this.dbPool = dbPool;
    }

    public final void execute(Command command, TransferConfig config) {
        log.info("Executing {} for command: {}", config.getScenario(), command.getId());
        Result result = newResult(config.getScenario());
        try {
            dispatch(command, config, result);
        } catch (Exception e) {
            log.error("{} execution failed for command {}: {}", config.getScenario(), command.getId(), e.getMessage(), e);
            result.failWith(e);
        } finally {
            finalize(command, config, result);
        }
    }

    /**
     * 按 scenario + commandType 派发到对应 Handler。异常上抛,留给外层 execute 统一处理。
     */
    protected abstract void dispatch(Command command, TransferConfig config, Result result) throws Exception;

    /**
     * 收尾:组装 Command/Config 派生字段 → 写指令表(非 MultiNode 抑制) → 写结果表。
     * <p>MultiNode 模式(markChildrenCreated)下,指令表终态由 MergeFlowService 在
     * 所有桶合并完成后统一写入,此处跳过。结果表行由 Handler 在 createChildren 时写入,
     * 也一并跳过以避免重复。
     * UPDATE 指令表 + INSERT 结果表 包在事务中:避免"指令已置终态但结果表未写"
     * 的状态(下一个超时周期才兜底为 ERROR,期间业务可见性偏差)。
     */
    private void finalize(Command command, TransferConfig config, Result result) {
        result.markEnd(command, config);
        if (result.isSuppressStatusUpdate()) {
            return;
        }
        dbPool.getTransactionTemplate(config.getDbName()).execute(status -> {
            commandRepository.updateStatus(command.getId(), result.getResult());
            resultRepository.insert(result);
            return null;
        });
    }

    private Result newResult(TransferScenario scenario) {
        String direction = scenario.name().startsWith("UPLOAD")
                ? Result.DIRECTION_UPLOAD : Result.DIRECTION_DOWNLOAD;
        return Result.builder()
                .transferDirection(direction)
                .markStart()
                .build();
    }
}
