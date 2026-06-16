package com.fmsy.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日期工具 — 占位符解析用的两个固定格式。
 *
 * 格式：
 * - formatDate: yyyyMMdd
 * - formatDateTime: yyyyMMddHHmmss
 */
public final class DateUtils {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private DateUtils() {
    }

    /** 格式化日期（yyyyMMdd） */
    public static String formatDate(LocalDateTime dateTime) {
        if (dateTime == null) return "";
        return dateTime.format(DATE_FORMATTER);
    }

    /** 格式化日期时间（yyyyMMddHHmmss） */
    public static String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) return "";
        return dateTime.format(DATETIME_FORMATTER);
    }
}