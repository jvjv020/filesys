package com.fmsy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 应用配置类 - 统一管理FMSY的各项配置参数
 *
 * 配置来源：application.yml中的app.*配置项
 *
 * 主要配置组：
 * - node: 当前节点标识，用于多节点协调
 * - polling: 轮询配置（间隔、批量大小、超时）
 * - download: 下载特定配置（桶批量、轮询上限）
 *
 * <p>注：app.log.path 仍由 application.yml 提供,供 logback-spring.xml 通过
 * {@code ${app.log.path}} 占位符读取,无需 Java 绑定类。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {
    /** 当前节点ID，用于多节点环境区分不同处理节点 */
    private Node node = new Node();
    /** 轮询服务配置 */
    private Polling polling = new Polling();
    /** 下载配置 */
    private Download download = new Download();

    /** 节点配置 */
    @Data
    public static class Node {
        /** 节点唯一标识 */
        private String id;
    }

    /** 轮询配置 */
    @Data
    public static class Polling {
        /** 轮询间隔（秒），默认10秒 */
        private int interval = 10;
        /** 每次轮询获取的命令数量，默认20 */
        private int batchSize = 20;
        /** 任务超时时间（小时），超时后自动释放供其他节点抢接 */
        private int taskTimeoutHours = 1;
    }

    /** 下载配置 */
    @Data
    public static class Download {
        /** 桶批量大小 */
        private int bucketBatchSize = 3;
        /** S子命令外层轮询最大迭代次数(防止极端情况下空转或bug导致死循环,默认1000) */
        private int maxPollIterations = 1000;
    }
}