package com.fmsy.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * FTP连接池配置
 *
 * 功能说明：
 * - 支持配置多个FTP服务器连接
 * - 每个连接包含：连接信息、连接池参数、故障转移、健康检查
 *
 * 配置参数：
 * - 主机地址、端口、用户名、密码、超时时间
 * - 连接池：最大连接数、最大空闲、核心空闲数
 * - 故障转移：启用开关、最大重试次数
 * - 健康检查：启用开关、检查间隔、检查超时
 */
@Configuration
@ConfigurationProperties(prefix = "ftp.config")
public class FtpPoolConfig {

    private List<FtpConfig> configs;

    /** FTP单个连接配置 */
    @Data
    public static class FtpConfig {
        private String id;
        private String host;
        private int port = 21;
        private String username;
        @ToString.Exclude
        @EqualsAndHashCode.Exclude
        private String password;
        private int timeout = 30000;
        private Pool pool = new Pool();
        private Failover failover = new Failover();
        private HealthCheck healthCheck = new HealthCheck();

        /** 连接池参数 */
        @Data
        public static class Pool {
            private int maxTotal = 10;
            /** 空闲连接最大存活秒数,超时自动淘汰(默认300=5分钟) */
            private int maxIdleTimeSeconds = 300;
        }

        /** 故障转移参数 */
        @Data
        public static class Failover {
            private boolean enabled = false;
            private int maxRetries = 2;
        }

        /** 健康检查参数 */
        @Data
        public static class HealthCheck {
            private boolean enabled = false;
            private int intervalSeconds = 30;
        }
    }

    public List<FtpConfig> getConfigs() {
        return configs;
    }

    public void setConfigs(List<FtpConfig> configs) {
        this.configs = configs;
    }
}