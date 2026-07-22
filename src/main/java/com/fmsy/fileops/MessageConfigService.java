package com.fmsy.fileops;

import com.fmsy.model.MessageConfig;
import com.fmsy.repository.MessageConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 消息配置加载服务 — 启动时从 DB 加载消息配置到内存缓存。
 *
 * <p>MSG 后置操作通过 {@link TransferConfig} 的 类别代号+控制代号 查到此服务，
 * 获取通道类型、目标地址和消息模板，委托 {@link MessageSender} 发送。
 */
@Slf4j
@Service
public class MessageConfigService {

    private final MessageConfigRepository repository;
    private final Map<String, MessageConfig> configMap = new HashMap<>();

    public MessageConfigService(MessageConfigRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void loadConfigs() {
        log.info("Loading message configurations...");
        try {
            for (MessageConfig config : repository.loadAll()) {
                String key = buildKey(config.getCategoryCode(), config.getControlCode());
                configMap.put(key, config);
                log.debug("Loaded message config: {}", key);
            }
            log.info("Loaded {} message configurations", configMap.size());
        } catch (Exception e) {
            log.warn("Failed to load message configs: {}", e.getMessage());
        }
    }

    /**
     * 按类别代号+控制代号查找消息配置。
     */
    public MessageConfig getConfig(String categoryCode, String controlCode) {
        return configMap.get(buildKey(categoryCode, controlCode));
    }

    private static String buildKey(String cat, String ctrl) {
        return cat + "_" + ctrl;
    }
}