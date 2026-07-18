package com.fmsy.polling;

import com.fmsy.config.AppConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.lifecycle.ShutdownService;
import com.fmsy.model.Command;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.transfer.TransferService;
import com.fmsy.util.ColumnNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 轮询服务 — 定时从数据库查询待处理的命令并竞争执行权。
 *
 * <p>本类只负责调度入口、上下文维护和异步任务包装,不参与单轮派发循环:
 * <ul>
 *   <li>本轮 ready 命令的派发循环(约束检查 → 竞争 → 配置 → submit)由
 *       {@link BatchDispatcher#dispatch} 承担</li>
 *   <li>本轮线程池的 lazy 创建 + 派发完毕 shutdown 由 BatchDispatcher 内部管理</li>
 * </ul>
 *
 * <p>每轮轮询处理流程:
 * <ol>
 *   <li>释放超时任务(置 ERROR + 写结果)</li>
 *   <li>加载当前正在处理的任务到内存(供串行约束检查)</li>
 *   <li>查询 ready 命令,交给 BatchDispatcher 派发</li>
 *   <li>异步任务由 runTransfer 包装 try-catch + finally endTask,异常不逃逸到线程池</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PollingService {

    private final AppConfig appConfig;
    private final ShutdownService shutdownService;
    private final ResultRepository resultRepository;
    private final TransferService transferService;
    private final CommandRepository commandRepository;
    private final BatchDispatcher batchDispatcher;

    /** 正在处理的任务映射表,键:categoryCode_controlCode */
    private final Map<String, CommandProcessingTracker> processingMap = new ConcurrentHashMap<>();

    /**
     * 定时轮询方法。
     * 使用 @Scheduled 实现固定间隔轮询,间隔由 app.polling.interval 配置(默认 10 秒)。
     */
    @Scheduled(fixedDelayString = "${app.polling.interval:10}000")
    public void poll() {
        if (shutdownService.isShuttingDown()) {
            return;
        }

        try {
            // 1. 释放超时任务
            releaseTimeoutTasks();
            // 2. 加载正在处理的任务(供本轮约束检查)
            loadProcessingCommands();
            // 3. 查询待处理命令
            List<Command> readyCommands = commandRepository.findReadyCommands(
                    appConfig.getPolling().getBatchSize());
            // 4. 派发:串行约束检查 → 竞争 → 配置 → submit
            batchDispatcher.dispatch(readyCommands, processingMap, this::runTransfer);
        } catch (DataAccessException e) {
            log.error("Polling failed due to database error, will retry next cycle: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("Polling failed with unexpected error, will retry next cycle: {}", e.getMessage(), e);
        }
    }

    /**
     * 释放超时任务。
     * 将超过 taskTimeoutHours 仍未完成的任务标记为 ERROR,记录到结果表,便于其他节点抢接。
     */
    private void releaseTimeoutTasks() {
        try {
            String nodeId = appConfig.getNode().getId();
            int timeoutHours = appConfig.getPolling().getTaskTimeoutHours();

            commandRepository.releaseTimeoutCommands(nodeId, timeoutHours, ColumnNames.STATUS_ERROR);

            var timeoutJobs = commandRepository.findTimeoutJobs(nodeId, timeoutHours);
            for (var job : timeoutJobs) {
                Long id = ((Number) job.get(ColumnNames.ID)).longValue();
                String categoryCode = (String) job.get(ColumnNames.CATEGORY_CODE);
                String controlCode = (String) job.get(ColumnNames.CONTROL_CODE);
                resultRepository.insertSimple(id, categoryCode, controlCode, ColumnNames.STATUS_ERROR, "执行超时自动释放");
                log.info("Released timeout command: {}", id);
            }
        } catch (DataAccessException e) {
            log.error("Failed to release timeout tasks, will retry next cycle: {}", e.getMessage(), e);
        }
    }

    /**
     * 加载当前正在处理的任务到内存。
     * 用于串行约束检查:确保相同 categoryCode+controlCode 的命令串行执行。
     */
    private void loadProcessingCommands() {
        try {
            List<Map<String, Object>> rows = commandRepository.findProcessingCommands();
            Map<String, CommandProcessingTracker> newMap = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String key = row.get(ColumnNames.CATEGORY_CODE) + "_" + row.get(ColumnNames.CONTROL_CODE);
                CommandProcessingTracker info = newMap.computeIfAbsent(key, k -> new CommandProcessingTracker());
                String node = (String) row.get(ColumnNames.PROCESSING_NODE);
                String cmdType = (String) row.get(ColumnNames.COMMAND_TYPE);
                String extraInfo = (String) row.get(ColumnNames.EXTRA_INFO);
                Object idObj = row.get(ColumnNames.ID);
                String cmdId = idObj == null ? null : idObj.toString();
                info.addNode(node);
                if (CommandType.COORDINATED.code().equals(cmdType)) {
                    info.setHasSType(true);
                    if (extraInfo != null) {
                        info.recordMainId(node, extraInfo);
                    }
                } else {
                    info.recordMainId(node, cmdId);
                }
            }
            processingMap.clear();
            processingMap.putAll(newMap);
        } catch (DataAccessException e) {
            log.error("Failed to load processing commands, will retry next cycle: {}", e.getMessage(), e);
        }
    }

    /**
     * 异步执行入口(运行在批处理线程池线程)。
     *
     * <p>包装 try/catch + finally endTask,保证:
     * <ul>
     *   <li>异常不会逃逸到线程池(避免线程被回收)</li>
     *   <li>无论成功/异常都归还 ShutdownService 计数,关闭流程可正常唤醒</li>
     * </ul>
     */
    void runTransfer(Long commandId, String direction) {
        try {
            transferService.process(commandId, direction);
        } catch (Throwable t) {
            log.error("Async processing failed for command: {}, direction: {}", commandId, direction, t);
        } finally {
            shutdownService.endTask();
        }
    }

    /**
     * 创建本轮独立的批处理线程池(生产环境默认实现,供 BatchDispatcher 注入)。
     *
     * <p>设计要点:
     * <ul>
     *   <li>corePoolSize = maxPoolSize = batchSize,无队列:本批命令并发度 = batchSize</li>
     *   <li>AbortPolicy:池满时直接拒绝,由调用方按需 endTask / log</li>
     *   <li>daemon 线程,不阻塞 JVM 退出</li>
     *   <li>线程名前缀含时间戳,便于日志区分不同轮次</li>
     * </ul>
     */
    public static ExecutorService createThreadPoolBatchExecutor(int batchSize) {
        int size = Math.max(1, batchSize);
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(size);
        exec.setMaxPoolSize(size);
        exec.setQueueCapacity(0);
        exec.setKeepAliveSeconds(60);
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        exec.setThreadNamePrefix("fmsy-batch-" + System.currentTimeMillis() + "-");
        exec.setDaemon(true);
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.setAwaitTerminationSeconds(0);
        exec.initialize();
        return exec.getThreadPoolExecutor();
    }
}
