package com.fmsy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FMSY (File Transfer Management System) 应用主入口
 *
 * 功能说明：
 * - 负责启动Spring Boot应用程序
 * - 启用定时任务调度（用于轮询命令表）
 * - 启用配置属性绑定（用于加载application.yml配置）
 *
 * 应用程序负责在FTP服务器和GaussDB数据库之间传输数据文件，
 * 支持DBF/XML/CSV/TXT等多种文件格式的双向转换。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties
public class FmsyApplication {

    public static void main(String[] args) {
        SpringApplication.run(FmsyApplication.class, args);
    }
}