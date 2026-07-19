package com.fmsy.transfer.placeholder;

import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ResolvedPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 占位符解析器 — 统一 {var} 语法。
 *
 * <p>废弃旧 $X$ 语法，全部改为 {X}。支持三类变量：
 *
 * <ul>
 *   <li><b>时间类</b>：{YYYYMMDD} {YYYMMDDHHmmss} {yyyymmdd} {now} {date} {time}</li>
 *   <li><b>数据类</b>：{EXTRA_INFO} {字段名} — 从 context Map 中按 key 查找</li>
 *   <li><b>文件衍生类</b>：{stem} {name} {ext} {dir} {dn} {up} — 从 ResolvedPath 取</li>
 * </ul>
 *
 * <p>解析顺序：时间类 → 文件衍生类 → 数据类（按 Map 逐项替换）。
 * 若 context 中找不到对应键，记录 WARN 并保留原占位符。
 */
@Slf4j
@Component
public class PlaceholderResolver {

    // ==================== 时间类正则 ====================
    private static final Pattern BRACE_PATTERN = Pattern.compile("\\{([A-Za-z0-9_]+)\\}");

    /**
     * 解析路径占位符（仅使用 context，无 ResolvedPath）。
     */
    public String resolve(String template, Map<String, String> context) {
        return resolve(template, context, null);
    }

    /**
     * 解析路径占位符（完整版）。
     *
     * @param template 含 {var} 的模板字符串
     * @param context  字段值上下文（{EXTRA_INFO} {字段名} 等）
     * @param fileInfo 文件衍生信息，可为 null
     * @return 替换后的字符串
     */
    public String resolve(String template, Map<String, String> context, ResolvedPath fileInfo) {
        if (template == null) return null;

        // Phase 1: 替换时间类占位符
        String result = resolveTimePlaceholders(template);

        // Phase 2: 替换文件衍生类占位符（如果有 fileInfo）
        if (fileInfo != null) {
            result = resolveFileInfo(result, fileInfo);
        }

        // Phase 3: 替换数据类占位符（从 context 查找）
        if (context != null && !context.isEmpty()) {
            result = resolveContext(result, context);
        } else {
            warnUnresolved(result);
        }

        return result;
    }

    // ==================== Phase 1: 时间类 ====================

    private String resolveTimePlaceholders(String template) {
        LocalDateTime now = LocalDateTime.now();
        return template
                .replace("{YYYYMMDDHHmmss}", formatDateTime(now))
                .replace("{now}", formatDateTime(now))
                .replace("{YYYYMMDD}", formatDate(now))
                .replace("{date}", formatDate(now))
                .replace("{yyyymmdd}", formatDate(now))
                .replace("{time}", formatTime(now));
    }

    private static String formatDate(LocalDateTime dt) {
        return String.format("%04d%02d%02d", dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth());
    }

    private static String formatDateTime(LocalDateTime dt) {
        return String.format("%04d%02d%02d%02d%02d%02d",
                dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(),
                dt.getHour(), dt.getMinute(), dt.getSecond());
    }

    private static String formatTime(LocalDateTime dt) {
        return String.format("%02d%02d%02d", dt.getHour(), dt.getMinute(), dt.getSecond());
    }

    // ==================== Phase 2: 文件衍生类 ====================

    private String resolveFileInfo(String template, ResolvedPath fileInfo) {
        return TransferSupport.expandPathVariables(template, fileInfo);
    }

    // ==================== Phase 3: 数据类 ====================

    private String resolveContext(String template, Map<String, String> context) {
        Matcher matcher = BRACE_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            // 跳过已在 Phase 1/2 处理过的关键字
            if (isTimeKeyword(name) || isFileInfoKeyword(name)) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String replacement = context.get(name);
            if (replacement != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                log.warn("Unresolved placeholder, keep as-is: {{{}}}", name);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private void warnUnresolved(String template) {
        Matcher m = BRACE_PATTERN.matcher(template);
        if (m.find()) {
            log.warn("Unresolved placeholder (no context), keep as-is: {{{}}}", m.group(1));
        }
    }

    private static boolean isTimeKeyword(String name) {
        return "YYYYMMDD".equals(name) || "YYYYMMDDHHmmss".equals(name)
                || "yyyymmdd".equals(name) || "now".equals(name)
                || "date".equals(name) || "time".equals(name);
    }

    private static boolean isFileInfoKeyword(String name) {
        return "stem".equals(name) || "name".equals(name) || "ext".equals(name)
                || "dir".equals(name) || "dn".equals(name) || "up".equals(name);
    }
}
