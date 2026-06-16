package com.fmsy.lifecycle;

import com.fmsy.exception.TransferException;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TransferConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置加载服务 - 应用启动时从数据库加载传输配置到内存
 *
 * <p>加载时机由 {@link StartupService#onApplicationReady} 触发,
 * 不使用 {@code @PostConstruct} 以避免与 {@code StartupService} 重复加载。</p>
 *
 * 功能说明：
 * - loadConfigs: 从数据库加载所有有效配置
 * - getConfig: 根据类别代号和控制代号获取配置
 *
 * 配置缓存在configMap中，以categoryCode_controlCode为键
 * 用于命令执行时查询传输规则
 *
 * <p>Phase 1 重构:本类不再持有 SQL,只做"加载到内存 + 外部文件合并"编排,
 * 由 {@link TransferConfigRepository} 负责 SQL 访问。
 */
@Slf4j
@Service
public class ConfigLoaderService {

    private final TransferConfigRepository configRepository;
    private final Map<String, TransferConfig> configMap = new HashMap<>();

    public ConfigLoaderService(TransferConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    /**
     * 从数据库加载所有有效配置
     * 查询条件：状态为'VALID'的传输配置记录
     */
    public void loadConfigs() {
        log.info("Loading transfer configurations...");
        try {
            for (TransferConfig config : configRepository.loadAll()) {
                String key = config.getCategoryCode() + "_" + config.getControlCode();
                configMap.put(key, config);
                log.info("Loaded config: {}", key);
            }
            log.info("Loaded {} transfer configurations", configMap.size());
        } catch (Exception e) {
            log.error("Failed to load transfer configurations: {}", e.getMessage(), e);
        }
    }

    /**
     * 根据类别代号和控制代号获取配置
     * @param categoryCode 类别代号
     * @param controlCode 控制代号
     * @return 对应配置，不存在返回null
     */
    public TransferConfig getConfig(String categoryCode, String controlCode) {
        String key = categoryCode + "_" + controlCode;
        return configMap.get(key);
    }

    /**
     * 查询传输配置(不存在时抛异常)
     * @throws TransferException 配置不存在时抛出
     */
    public TransferConfig getConfigOrThrow(String categoryCode, String controlCode) {
        TransferConfig config = getConfig(categoryCode, controlCode);
        if (config == null) {
            log.error("Transfer config not found: {}_{}", categoryCode, controlCode);
            throw new TransferException("CONFIG_NOT_FOUND",
                "Transfer config not found: " + categoryCode + "_" + controlCode);
        }
        return config;
    }

    /**
     * 查询传输配置,配置缺失时返回 null(不抛异常)。
     * <p>用于"配置可能在执行过程中被卸载"的兜底分支,例如:
     * <ul>
     *   <li>{@code TransferService.process} / {@code BatchDispatcher.dispatch}:主命令竞争成功后
     *       业务方已派发,后续路径配置消失时不应再阻断主流程</li>
     *   <li>{@code DetailPollingService.writeSubCommandResult}:子命令执行期间配置被卸载
     *       也得能写一条结果表</li>
     *   <li>{@code ChildCommandMonitor.updateMainCommandStatus}:子节点监控完成时配置刚好被卸载
     *       也不阻塞 TOTAL_FLAG 流程</li>
     * </ul>
     * <p>与 {@link #getConfigOrThrow} 的区别:这里只 WARN,不抛异常,调用方按 null 自行降级。
     */
    public TransferConfig getConfigOrDefault(String categoryCode, String controlCode) {
        TransferConfig config = getConfig(categoryCode, controlCode);
        if (config == null) {
            log.warn("Transfer config not found (returning null): {}_{}", categoryCode, controlCode);
        }
        return config;
    }
}
