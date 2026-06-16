package com.fmsy.config;

import com.fmsy.polling.PollingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.function.IntFunction;

/**
 * 批处理线程池工厂配置
 *
 * <p>为 {@code PollingService} 每轮轮询提供独立的批处理线程池。
 * 每轮创建全新 {@code ExecutorService},与上一轮 / 下一轮隔离,避免不同批次命令相互影响。
 */
@Configuration
public class AsyncConfig {

    @Bean
    public IntFunction<ExecutorService> batchExecutorFactory() {
        return PollingService::createThreadPoolBatchExecutor;
    }
}