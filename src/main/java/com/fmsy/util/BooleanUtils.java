package com.fmsy.util;

/**
 * 布尔工具 — 统一项目中 "Y" 字符串到 boolean 的解析。
 *
 * <p>传输配置表中多个标志字段(清表标志 / 串行标志 / 覆盖标志等)用 {@code "Y"} 表示 true。
 * 直接写 {@code "Y".equals(flag)} 散弹式判断会:
 * <ul>
 *   <li>忽略大小写不一致(部分调用方用 {@code equalsIgnoreCase})</li>
 *   <li>无法表达"非 Y 即 false"的统一语义</li>
 * </ul>
 *
 * <p>本工具统一为 {@link #isYes(String)} — null-safe,大小写不敏感,非 {@code "Y"} 一律 false。
 */
public final class BooleanUtils {

    private BooleanUtils() {
    }

    /**
     * 判断字符串是否表示 true (大小写不敏感,仅 {@code "Y"} 视为 true)。
     * @param value 输入字符串(null 安全)
     * @return true 仅当 value 等于 "Y" / "y";其余(包括 null、空串、其他字符)一律 false
     */
    public static boolean isYes(String value) {
        return "Y".equalsIgnoreCase(value);
    }
}
