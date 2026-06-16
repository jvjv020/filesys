package com.fmsy.fileops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.IOException;

/**
 * 消息发送服务 - 迭代 #10 真实发送通道
 *
 * <p>支持的发送渠道:
 * <ul>
 *   <li>LOG: 写 SLF4J INFO 日志(可指定 logger name)</li>
 *   <li>WEBHOOK: HTTP POST application/json,body 为 {@code {"message":"..."}}</li>
 * </ul>
 *
 * <p>未实现的渠道(MAIL / SMS / MQ)保持 log warn 占位,留作扩展。
 *
 * <p>目标配置格式: {@code type:target}
 * <ul>
 *   <li>{@code LOG:my.appender} - 指定 logger name</li>
 *   <li>{@code LOG:} - 使用默认 logger "fmsy.message"</li>
 *   <li>{@code WEBHOOK:https://example.com/hook}</li>
 * </ul>
 */
@Slf4j
@Component
public class MessageSender {

    /** 默认 logger name(LOG 通道未指定 target 时使用) */
    public static final String DEFAULT_LOGGER_NAME = "fmsy.message";

    /** 通道类型常量 */
    public static final String TYPE_LOG = "LOG";
    public static final String TYPE_WEBHOOK = "WEBHOOK";

    /** 共享 HttpClient(线程安全) - 超时 5 秒 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 发送消息(根据 targetConfig 路由通道)
     *
     * @param targetConfig 目标配置,格式 {@code type:target}
     * @param message      消息内容
     */
    public void send(String targetConfig, String message) {
        if (targetConfig == null || targetConfig.isEmpty()) {
            log.warn("No target config for message sending");
            return;
        }

        String[] parts = targetConfig.split(":", 2);
        if (parts.length < 2) {
            log.warn("Invalid target config format: {}", targetConfig);
            return;
        }

        String type = parts[0].trim().toUpperCase();
        String target = parts[1].trim();

        switch (type) {
            case TYPE_LOG:
                sendLog(target, message);
                break;
            case TYPE_WEBHOOK:
                sendWebhook(target, message);
                break;
            case "MAIL":
                log.warn("SEND_MESSAGE MAIL not implemented. Target: {}, Message: {}", target, message);
                break;
            case "SMS":
                log.warn("SEND_MESSAGE SMS not implemented. Target: {}, Message: {}", target, message);
                break;
            case "MQ":
                log.warn("SEND_MESSAGE MQ not implemented. Target: {}, Message: {}", target, message);
                break;
            default:
                log.warn("Unknown message type: {}", type);
        }
    }

    /**
     * LOG 通道 - 写 SLF4J INFO 日志
     *
     * @param target  logger name(空时使用默认 "fmsy.message")
     * @param message 消息内容
     */
    private void sendLog(String target, String message) {
        String loggerName = (target == null || target.isEmpty()) ? DEFAULT_LOGGER_NAME : target;
        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(loggerName);
        logger.info("[MESSAGE] {}", message);
    }

    /**
     * WEBHOOK 通道 - HTTP POST application/json
     *
     * <p>迭代 #19: 引入指数退避重试,弱网下提升送达率。
     * 最大尝试 3 次(初次 + 2 次重试),退避间隔 100ms / 500ms。
     * 触发重试: IOException / InterruptedException / HTTP 5xx / 429。
     * 不重试: HTTP 4xx(除 429)。
     * 异常时仅记 WARN/ERROR,不抛出(消息发送失败不应阻塞主流程)。
     */
    private void sendWebhook(String url, String message) {
        if (url == null || url.isEmpty()) {
            log.warn("WEBHOOK target URL is empty, skip");
            return;
        }
        String body = "{\"message\":\"" + escape(message) + "\"}";
        boolean delivered = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                log.info("Webhook send response: status={}, body={}, attempt={}", status, resp.body(), attempt);
                if (status >= 200 && status < 300) {
                    delivered = true;
                    break;
                }
                if (!shouldRetry(status)) {
                    break;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Webhook send interrupted. URL: {}, Message: {}", url, message);
                return;
            } catch (IOException ioe) {
                log.warn("Webhook send IO error (attempt {}/3). URL: {}, Error: {}",
                        attempt + 1, url, ioe.getMessage());
            } catch (Exception e) {
                log.warn("Webhook send failed. URL: {}, Error: {}", url, e.getMessage());
                break;
            }
            long backoff = retryBackoffMs(attempt);
            if (backoff > 0) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Webhook retry sleep interrupted. URL: {}, Message: {}", url, message);
                    return;
                }
            }
        }
        if (!delivered) {
            log.error("Webhook send ultimately failed after retries. URL: {}, Message: {}", url, message);
        }
    }

    /**
     * 给定已收到的 HTTP statusCode,判断是否需要重试。
     *
     * <p>5xx 与 429 返回 true(服务端临时错误 / 限流,可重试);
     * 2xx / 3xx / 其他 4xx 返回 false(成功 / 重定向 / 客户端错误,不再重试)。
     *
     * @param statusCode HTTP 响应状态码
     * @return 是否需要重试
     */
    static boolean shouldRetry(int statusCode) {
        if (statusCode >= 500 && statusCode < 600) {
            return true;
        }
        return statusCode == 429;
    }

    /**
     * 给定 attempt index(0 = 初次,1 = 第一次重试,2 = 第二次重试),返回退避 sleep 毫秒数。
     *
     * <p>attempt=0 → 100,attempt=1 → 500,attempt≥2 → 0(无意义但兜底)。
     *
     * @param attemptIndex 当前尝试序号(0 起始)
     * @return sleep 毫秒数
     */
    static long retryBackoffMs(int attemptIndex) {
        if (attemptIndex == 0) {
            return 100L;
        }
        if (attemptIndex == 1) {
            return 500L;
        }
        return 0L;
    }

    /**
     * JSON 字符串转义 - 处理 {@code "} 与换行
     *
     * <p>用于手工拼装 JSON body 时保证消息体可解析。
     */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
