package com.fmsy.lifecycle;

import com.fmsy.config.AppConfig;
import com.fmsy.config.DataSourceConfig;
import com.fmsy.db.JdbcTemplateWrapper;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.util.ColumnNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 启动服务 - 应用启动时的初始化和恢复
 *
 * 启动流程（按顺序执行）：
 * 1. initializeNodeId: 初始化节点ID
 * 2. configLoader.loadConfigs: 从数据库加载传输配置
 * 3. recoverAbnormalJobs: 恢复本节点异常中断的任务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StartupService {

    private final DataSourceConfig.DbPool dbPool;
    private final AppConfig appConfig;
    private final ConfigLoaderService configLoader;
    private final ResultRepository resultRepository;
    private final CommandRepository commandRepository;

    /** 应用就绪事件监听 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("FMSY application starting...");
        initializeNodeId();
        probeDatabase();
        configLoader.loadConfigs();
        recoverAbnormalJobs();
        log.info("FMSY application started successfully");
    }

    /**
     * 数据库连通性探测 - 在加载配置前执行
     * 执行SELECT 1测试主数据源可用性
     * 探测失败仅log.error，不阻止启动（系统应允许DB暂时不可用）
     */
    private void probeDatabase() {
        try {
            Integer result = commandRepository.probeDatabase();
            if (result != null && result == 1) {
                log.info("Database probe successful for {}", ColumnNames.DEFAULT_DB);
            } else {
                log.warn("Database probe returned unexpected value: {}", result);
            }
        } catch (Exception e) {
            log.error("Database probe failed for {}: {}", ColumnNames.DEFAULT_DB, e.getMessage(), e);
        }
    }

    /** 初始化本节点ID（优先从系统属性读取，否则用默认值） */
    private void initializeNodeId() {
        if (appConfig.getNode().getId() == null || appConfig.getNode().getId().isEmpty()) {
            appConfig.getNode().setId(System.getProperty("hostname", "node-unknown"));
        }
        log.info("Node ID: {}", appConfig.getNode().getId());
    }

    /** 恢复异常中断的任务：将本节点处理中的任务标记为跳过 */
    private void recoverAbnormalJobs() {
        String nodeId = appConfig.getNode().getId();
        var jobs = commandRepository.findProcessingJobs(nodeId);

        for (var job : jobs) {
            Long id = ((Number) job.get(ColumnNames.ID)).longValue();
            String categoryCode = (String) job.get(ColumnNames.CATEGORY_CODE);
            String controlCode = (String) job.get(ColumnNames.CONTROL_CODE);
            // UPDATE 指令表 + INSERT 结果表 原子化:避免"指令置 SKIPPED 但结果表未写"
            dbPool.getTransactionTemplate(ColumnNames.DEFAULT_DB).execute(status -> {
                commandRepository.updateStatus(id, ColumnNames.STATUS_SKIPPED);
                resultRepository.insertSimple(id, categoryCode, controlCode, ColumnNames.STATUS_SKIPPED, "异常中断恢复，跳过");
                return null;
            });
            log.info("Recovered abnormal job: {}", id);
        }
    }
}
