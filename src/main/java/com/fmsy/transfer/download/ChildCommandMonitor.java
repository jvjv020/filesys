package com.fmsy.transfer.download;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.util.LogUtils;
import com.fmsy.util.SystemConstants;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.transfer.TempTransferConfigFactory;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.lifecycle.ConfigLoaderService;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子命令监控器 - DOWNLOAD_MULTI_NODE场景
 *
 * 功能说明：
 * - 主命令创建S型子命令后，监控子命令完成情况
 * - 轮询检查所有子命令是否已结束（成功/跳过/失败）
 * - 所有子命令完成后更新主命令状态
 * - 若配置了GENERATE_TOTAL_FLAG，生成总标志文件
 *
 * 监控流程：
 * 1. 轮询统计已完成的子命令数量
 * 2. 达到预期数量则进入步骤3，否则继续等待
 * 3. 汇总所有明细状态，确定主命令最终状态
 * 4. 若allSuccess且配置了TOTAL_FLAG，生成总标志文件
 */
@Slf4j
@Service
public class ChildCommandMonitor {

    // 迭代 #17:EXTRA_INFO 列已变为 "mainId|baseFilePath" 格式,主命令 ID 仅占前段。
    // SQL 改用 LIKE 'mainId|%' 前缀匹配(主命令 ID 是 Long.toString,纯数字,通配符安全)。
    // baseFilePath 段中的 "|" 字符不影响前缀匹配语义(只匹配首段)。
    private final DetailRepository detailRepository;
    private final ConfigLoaderService configLoader;
    private final FtpPool ftpPool;
    private final TransferSupport transferSupport;
    private final ResultRepository resultRepository;
    private final CommandRepository commandRepository;
    private final DataSourceConfig.DbPool dbPool;
    private final TempTransferConfigFactory tempConfigFactory;

    public ChildCommandMonitor(DetailRepository detailRepository,
                               ConfigLoaderService configLoader, FtpPool ftpPool,
                               TransferSupport transferSupport,
                               ResultRepository resultRepository,
                               CommandRepository commandRepository,
                               DataSourceConfig.DbPool dbPool,
                               TempTransferConfigFactory tempConfigFactory) {
        this.detailRepository = detailRepository;
        this.configLoader = configLoader;
        this.ftpPool = ftpPool;
        this.transferSupport = transferSupport;
        this.resultRepository = resultRepository;
        this.commandRepository = commandRepository;
        this.dbPool = dbPool;
        this.tempConfigFactory = tempConfigFactory;
    }

    /** 后台调度器(daemon 线程,不阻塞 JVM 退出) */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "fmsy-child-monitor");
        t.setDaemon(true);
        return t;
    });

    /**
     * 启动后台监控,等待子命令完成。立即返回。
     * 使用 {@link ScheduledExecutorService} 以固定间隔轮询,避免 {@code Thread.sleep} 原始线程管理。
     *
     * <p>最大轮询次数由 {@link SystemConstants#MONITOR_MAX_ITERATIONS} 限制,
     * 超时后强制更新主命令为 ERROR,防止永久卡在 P 状态。
     *
     * @param mainCommandId    主命令 ID
     * @param expectedChildren 期望的子命令数量
     * @param dbName           主命令所在数据源
     */
    public void start(Long mainCommandId, int expectedChildren, String dbName) {
        LogUtils.setTaskId(mainCommandId);
        log.info("Starting monitor for main command: {}, expected children: {}",
                mainCommandId, expectedChildren);

        AtomicInteger iteration = new AtomicInteger(0);
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
            int iter = iteration.incrementAndGet();
            if (iter > SystemConstants.MONITOR_MAX_ITERATIONS) {
                log.error("Monitor timeout for main command: {}, reached max iterations",
                        mainCommandId);
                updateMainCommandStatusOnTimeout(mainCommandId, dbName);
                throw new RuntimeException("Monitor timeout for cmd " + mainCommandId);
            }

            int completed = countCompletedChildren(mainCommandId);
            log.info("Completed children: {}/{} (iteration {})",
                    completed, expectedChildren, iter);

            if (completed >= expectedChildren) {
                log.info("All children completed for main command: {}", mainCommandId);
                updateMainCommandStatus(mainCommandId, dbName);
                throw new RuntimeException("Monitor completed for cmd " + mainCommandId);
            }
        }, 0, SystemConstants.MONITOR_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 异常终止时自动取消调度(正常/超时都会抛 RuntimeException 触发)
        Thread monitorThread = new Thread(() -> {
            try {
                future.get();
            } catch (java.util.concurrent.CancellationException e) {
                log.debug("Monitor cancelled for cmd {}", mainCommandId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Monitor interrupted for cmd {}", mainCommandId);
                future.cancel(false);
            } catch (java.util.concurrent.ExecutionException e) {
                log.debug("Monitor completed for cmd {}: {}", mainCommandId, e.getCause().getMessage());
            }
        }, "fmsy-child-monitor-" + mainCommandId);
        monitorThread.setDaemon(false);
        monitorThread.start();
    }

    /** 统计已完成的子命令数量 */
    private int countCompletedChildren(Long mainCommandId) {
        // 迭代 #17:用 LIKE 'mainId|%' 前缀匹配,适配 EXTRA_INFO="mainId|baseFilePath" 新格式。
        // 主命令 ID 是 Long.toString 结果(纯数字),与 BucketDistributor.createChildCommands
        // 拼接前缀一致,无 SQL 注入风险。
        String likePattern = mainCommandId.toString() + "|%";
        return commandRepository.countCompletedChildren(likePattern);
    }

    /** 超时时更新主命令状态为ERROR */
    private void updateMainCommandStatusOnTimeout(Long mainCommandId, String dbName) {
        try {
            String resolvedDb = dbName != null ? dbName : ColumnNames.DEFAULT_DB;
            dbPool.getTransactionTemplate(resolvedDb).execute(status -> {
                commandRepository.updateMainStatus(mainCommandId, ColumnNames.STATUS_ERROR);
                resultRepository.insertSimple(mainCommandId, null, null, ColumnNames.STATUS_ERROR, "Monitor timeout", resolvedDb);
                return null;
            });
            log.info("Updated main command status to ERROR on timeout: {}", mainCommandId);
        } catch (Exception e) {
            log.error("Failed to update main command status on timeout: {}", mainCommandId, e.getMessage(), e);
        }
    }

    /**
     * 更新主命令状态
     * 根据所有明细状态汇总：全成功→SUCCESS，有错误→ERROR，有跳过→SKIPPED
     */
    private void updateMainCommandStatus(Long mainCommandId, String dbName) {
        List<Detail> details = detailRepository.findByCommandId(mainCommandId);

        boolean hasError = false;
        boolean hasSkip = false;
        boolean allSuccess = true;

        // 从第一个明细获取类别和控制代号（提升到外层以便汇总阶段使用）
        String categoryCode = null;
        String controlCode = null;

        for (Detail detail : details) {
            String status = detail.getStatus();
            if (ColumnNames.STATUS_ERROR.equals(status)) hasError = true;
            if (ColumnNames.STATUS_SKIPPED.equals(status)) hasSkip = true;
            if (!ColumnNames.STATUS_SUCCESS.equals(status)) allSuccess = false;

            // 从第一个明细获取类别和控制代号
            if (categoryCode == null && detail.getCategoryCode() != null) {
                categoryCode = detail.getCategoryCode();
                controlCode = detail.getControlCode();
            }
        }

        // 汇总确定最终状态
        String finalStatus;
        if (allSuccess) {
            finalStatus = ColumnNames.STATUS_SUCCESS;
        } else if (hasError) {
            finalStatus = ColumnNames.STATUS_ERROR;
        } else if (hasSkip) {
            finalStatus = ColumnNames.STATUS_SKIPPED;
        } else {
            finalStatus = ColumnNames.STATUS_ERROR;
        }

        // 复用本方法已查到的 categoryCode/controlCode,不再二次 findByCommandId
        String description = String.format("Multi-node summary: %d detail(s)", details.size());
        String resolvedDb = dbName != null ? dbName : ColumnNames.DEFAULT_DB;
        // UPDATE 指令表 + INSERT 结果表 原子化,避免"指令置终态但结果表未写"
        String finalCategoryCode = categoryCode;
        String finalControlCode = controlCode;
        dbPool.getTransactionTemplate(resolvedDb).execute(status -> {
            commandRepository.updateMainStatus(mainCommandId, finalStatus);
            resultRepository.insertSimple(mainCommandId, finalCategoryCode, finalControlCode, finalStatus, description, resolvedDb);
            return null;
        });
        log.info("Updated main command status: {} -> {}", mainCommandId, finalStatus);

        // 生成总标志文件
        // 用新关键字 TOTAL 过滤,并用 ResolvedPath 传入文件衍生信息
        if (allSuccess && categoryCode != null && controlCode != null) {
            // 主命令下所有桶成功后若配置刚好被卸载,只跳过 TOTAL,不阻塞监控
            TransferConfig config = configLoader.getConfigOrDefault(categoryCode, controlCode);
            if (config == null && mainCommandId != null) {
                Command mainCmd = commandRepository.findById(mainCommandId);
                if (mainCmd != null && mainCmd.getCommandType() == CommandType.TEMPORARY) {
                    config = tempConfigFactory.build(mainCmd);
                }
            }
            if (config != null) {
                String postOps = config.getPostOperations();
                String totalFlagOnlyOps = FlagFileService.filterOpsByType(postOps, "TOTAL");
                if (totalFlagOnlyOps != null) {
                    log.info("Generating total flag for main command: {}", mainCommandId);
                    ResolvedPath fileInfo = ResolvedPath.of(config.getFilePath());
                    ftpPool.withClient(config.getFtpName(), client -> {
                        transferSupport.postProcess(client, totalFlagOnlyOps, fileInfo, null);
                    });
                }
            }
        }
    }
}
