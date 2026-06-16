package com.fmsy.polling;

import com.fmsy.config.AppConfig;
import com.fmsy.lifecycle.ConfigLoaderService;
import com.fmsy.lifecycle.ShutdownService;

import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * 单轮命令的批量派发器 — 封装"串行约束检查 → 竞争 → 配置查询 → 派发到线程池"完整循环。
 *
 * <p>由 {@link PollingService#poll()} 每轮调用一次,生命周期与一次轮询周期绑定:
 * lazy 创建本轮 ExecutorService,派发完毕在 finally 中 shutdown(),下一轮重建。
 *
 * <p>职责边界:
 * <ul>
 *   <li>不负责从 DB 取 ready commands(由 PollingService 调用 CommandRepository)</li>
 *   <li>不负责超时任务释放、processingMap 加载(由 PollingService)</li>
 *   <li>不负责异步任务的 try-catch 包装(由 PollingService.runTransfer 兜底)</li>
 *   <li>只负责本轮派发循环:约束 → 竞争 → 配置 → submit</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDispatcher {

    private final AppConfig appConfig;
    private final SerialConstraintChecker constraintChecker;
    private final ShutdownService shutdownService;
    private final ConfigLoaderService configLoader;
    private final CommandRepository commandRepository;
    /** 每轮 poll 创建的批处理线程池工厂(batchSize → ExecutorService) */
    private final IntFunction<ExecutorService> batchExecutorFactory;

    /**
     * 派发一轮 ready 命令到本轮线程池。
     *
     * @param readyCommands  本轮 ready 的命令
     * @param processingMap  当前正在处理的任务映射(串行约束检查用)
     * @param transferRunner 提交到线程池的任务体(由 PollingService 提供,封装 try-catch)
     */
    public void dispatch(List<Command> readyCommands,
                         Map<String, CommandProcessingTracker> processingMap,
                         TransferRunner transferRunner) {
        if (readyCommands.isEmpty()) {
            return;
        }
        // 本轮批处理线程池:lazy 创建,仅在有命令派发时实例化,避免空轮询浪费
        ExecutorService batchExecutor = null;
        AtomicInteger dispatched = new AtomicInteger(0);
        String nodeId = appConfig.getNode().getId();

        try {
            for (Command cmd : readyCommands) {
                if (shutdownService.isShuttingDown()) {
                    break;
                }
                // 1. 串行约束检查(同类命令不能在多节点并发)
                if (!constraintChecker.check(cmd, processingMap)) {
                    continue;
                }
                // 2. 竞争执行权
                if (!commandRepository.compete(cmd.getId(), nodeId)) {
                    continue;
                }
                log.debug("Command competed successfully: {}", cmd.getId());
                // 3. 查询配置 - 配置缺失时按需求 7.4.2.4 置 E + 写结果
                TransferConfig config = configLoader.getConfigOrDefault(cmd.getCategoryCode(), cmd.getControlCode());
                if (config == null) {
                    log.error("No config found for command: {}, marking as ERROR", cmd.getId());
                    commandRepository.markErrorWithResult(cmd.getId(), cmd.getCategoryCode(),
                            cmd.getControlCode(), "Config not found: " + cmd.getCategoryCode() + "_" + cmd.getControlCode());
                    continue;
                }
                // 4. 异步派发到本轮独立的批处理线程池
                if (!shutdownService.beginTask()) {
                    log.info("Skipping dispatch for command {}: shutdown in progress", cmd.getId());
                    continue;
                }
                if (batchExecutor == null) {
                    batchExecutor = batchExecutorFactory.apply(appConfig.getPolling().getBatchSize());
                }
                Long commandId = cmd.getId();
                String direction = config.getScenario().isUpload()
                        ? Result.DIRECTION_UPLOAD : Result.DIRECTION_DOWNLOAD;
                dispatched.incrementAndGet();
                try {
                    batchExecutor.execute(() -> transferRunner.run(commandId, direction));
                } catch (RejectedExecutionException e) {
                    dispatched.decrementAndGet();
                    shutdownService.endTask();
                    log.error("Batch executor rejected command {}: {}", commandId, e.getMessage(), e);
                } catch (Exception e) {
                    dispatched.decrementAndGet();
                    shutdownService.endTask();
                    log.error("Failed to submit command {} to batch executor", commandId, e);
                }
            }
        } finally {
            if (batchExecutor != null) {
                log.debug("Closing batch executor ({} tasks dispatched this cycle)", dispatched.get());
                batchExecutor.shutdown();
            }
        }
    }

    /**
     * 异步任务执行回调 — 由 PollingService 提供,封装 try-catch + shutdown endTask。
     */
    @FunctionalInterface
    public interface TransferRunner {
        void run(Long commandId, String direction);
    }
}
