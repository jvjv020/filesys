package com.fmsy.transfer;

import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.TransferConfig;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import com.fmsy.transfer.placeholder.PlaceholderResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 传输场景公共 Support — 上传 / 下载 Handler 共用的工具方法。
 *
 * <p>Handler 通过构造器注入 {@link TransferSupport} 即可。包含 4 个跨方向方法 +
 * 1 个客户端借还模板。
 *
 * <ul>
 *   <li>{@link #resolveFilePath(String, Command)} — 路径模板占位符解析，返回 ResolvedPath</li>
 *   <li>{@link #preCheck(FtpClient, TransferConfig, ResolvedPath)} — 前置文件检查</li>
 *   <li>{@link #postProcess(FtpClient, TransferConfig, ResolvedPath, Map)} — 后置文件操作</li>
 *   <li>{@link #handleEmptyData(int, EmptyDataHandling)} — 空数据策略</li>
 *   <li>{@link #executeWithClient(String, FtpClientCallback)} — 主 FtpClient 借还模板</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferSupport {

    private final PlaceholderResolver placeholderResolver;
    private final FlagFileService flagFileService;
    private final FtpPool ftpPool;

    /**
     * 解析文件路径模板（使用命令的额外信息作为上下文），返回 ResolvedPath。
     */
    public ResolvedPath resolveFilePath(String template, Command command) {
        if (template == null) return null;
        String resolved = placeholderResolver.resolve(template, buildContext(command, null, null));
        return ResolvedPath.of(resolved);
    }

    /**
     * 解析文件路径模板，返回 ResolvedPath。
     */
    public ResolvedPath resolveFilePath(String template, Map<String, String> context) {
        if (template == null) return null;
        String resolved = placeholderResolver.resolve(template, context);
        return ResolvedPath.of(resolved);
    }

    /**
     * 将两个逗号分隔字符串按位置一一对应解析为 Map。
     * 委托给 {@link SplitFieldHelper#toMap}。
     * <p>例如: splitFieldValues("REGION,STATUS", "EAST,ACTIVE") → {"REGION" -> "EAST", "STATUS" -> "ACTIVE"}</p>
     */
    public static Map<String, String> splitFieldValues(String names, String values) {
        return SplitFieldHelper.toMap(names, values);
    }

    /**
     * 构造路径占位符上下文。
     */
    public Map<String, String> buildContext(Command command, String splitFields, String fieldValue) {
        Map<String, String> context = new HashMap<>();
        if (command != null && command.getExtraInfo() != null) {
            context.put("EXTRA_INFO", command.getExtraInfo());
        }
        if (splitFields != null && !splitFields.isEmpty()
                && fieldValue != null && !fieldValue.isEmpty()) {
            context.putAll(splitFieldValues(splitFields, fieldValue));
        }
        return context;
    }

    /**
     * 前置检查 — 委托给 FlagFileService.preCheck。
     *
     * @param fileInfo 数据文件的 ResolvedPath，用于路径继承
     * @return true=通过，false=跳过
     */
    public boolean preCheck(FtpClient client, TransferConfig config, ResolvedPath fileInfo) {
        String preOps = config.getPreOperations();
        return flagFileService.preCheck(client, preOps, fileInfo);
    }

    /**
     * 后置处理 — 从 config 读取后操作字符串，委托给 FlagFileService.process。
     *
     * @param fileInfo    数据文件的 ResolvedPath，用于路径继承和内容变量
     * @param extraValues 额外运行时值（如 {"C": "1234"}），可为 null
     */
    public void postProcess(FtpClient client, TransferConfig config,
                             ResolvedPath fileInfo, Map<String, String> extraValues) {
        String postOps = config.getPostOperations();
        if (postOps != null && !postOps.isEmpty()) {
            flagFileService.process(client, postOps, fileInfo, extraValues,
                    config.getCategoryCode(), config.getControlCode());
        }
    }

    /**
     * 后置处理 — 使用显式操作字符串（不走 config），供按类型过滤后（filterOpsByType）的场景。
     *
     * @param postOps     显式后置操作字符串，为 null/空时不执行
     * @param extraValues 额外运行时值（如 {"C": "1234"}），可为 null
     */
    public void postProcess(FtpClient client, String postOps,
                             ResolvedPath fileInfo, Map<String, String> extraValues) {
        if (postOps != null && !postOps.isEmpty()) {
            flagFileService.process(client, postOps, fileInfo, extraValues, null, null);
        }
    }

    /**
     * 空数据处理 — 根据配置处理无数据的情况。
     *
     * @param recordCount 实际记录数
     * @param handling    处理方式枚举
     * @return true=继续处理，false=应停止处理
     */
    public boolean handleEmptyData(int recordCount, EmptyDataHandling handling) {
        if (recordCount == 0) {
            switch (handling) {
                case ERROR:
                    log.error("Empty data encountered, configured to error");
                    return false;
                case SKIP:
                    log.info("Empty data encountered, configured to skip");
                    return false;
                case ALLOW:
                case SEND_EMPTY:
                    log.info("Empty data allowed");
                    return true;
                default:
                    return true;
            }
        }
        return true;
    }

    /**
     * 根据成功/失败/跳过计数判定主指令最终状态。
     */
    public static String determineMainStatus(boolean allSuccess, int failedCount, int skippedCount) {
        if (allSuccess) return ColumnNames.STATUS_SUCCESS;
        if (failedCount > 0) return ColumnNames.STATUS_ERROR;
        if (skippedCount > 0) return ColumnNames.STATUS_SKIPPED;
        return ColumnNames.STATUS_ERROR;
    }

    /**
     * 替换路径模板中的文件衍生变量 — 将 {stem}/{name}/{ext}/{dir}/{dn}/{up} 替换为实际值。
     */
    public static String expandPathVariables(String template, ResolvedPath fileInfo) {
        if (template == null || fileInfo == null) return template;
        return template
                .replace("{stem}", fileInfo.stem() != null ? fileInfo.stem() : "")
                .replace("{name}", fileInfo.name() != null ? fileInfo.name() : "")
                .replace("{ext}", fileInfo.ext() != null ? fileInfo.ext() : "")
                .replace("{dir}", fileInfo.dir() != null ? fileInfo.dir() : "")
                .replace("{dn}", fileInfo.dn() != null ? fileInfo.dn() : "")
                .replace("{up}", fileInfo.up() != null ? fileInfo.up() : "");
    }

    /**
     * 安全关闭线程池：先 shutdown，等待指定超时，超时未终止则 shutdownNow。
     * 委托给 {@link ThreadUtils#shutdownExecutor}。
     *
     * @param executor  待关闭的线程池
     * @param timeout   等待时长
     * @param unit      时间单位
     * @param poolName  线程池名称（仅用于日志）
     */
    public static void shutdownExecutor(ExecutorService executor, long timeout,
                                        TimeUnit unit, String poolName) {
        ThreadUtils.shutdownExecutor(executor, timeout, unit, poolName);
    }

    /**
     * 安全休眠 — 忽略中断后恢复中断标志，不抛异常。
     * 委托给 {@link ThreadUtils#safeSleep}。
     */
    public static void safeSleep(long ms) {
        ThreadUtils.safeSleep(ms);
    }

    /**
     * 主 FtpClient 借还模板。
     */
    public <T> T executeWithClient(String ftpName, FtpClientCallback<T> callback) throws Exception {
        FtpClient client = ftpPool.getClient(ftpName);
        try {
            return callback.run(client);
        } finally {
            client.close();
        }
    }

    @FunctionalInterface
    public interface FtpClientCallback<T> {
        T run(FtpClient client) throws Exception;
    }
}
