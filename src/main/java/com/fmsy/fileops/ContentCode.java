package com.fmsy.fileops;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 内容编号注册表 — 将配置中的两位数编号映射为实际内容模板。
 *
 * <p>前置检查（FLAG）和后置生成（FB / SF / TF）共用同一套编号，实现对称性。
 * 编号 {@code 00} 表示空内容：
 * <ul>
 *   <li>FLAG 场景：只检查文件存在，不比对内容</li>
 *   <li>生成场景：生成空文件</li>
 * </ul>
 *
 * <p>内容模板中的单字母模式码（L/S/M/C/N/D/T/F/X/E/P）在运行时由
 * {@link ContentEngine} 替换为实际值。
 */
@Getter
public enum ContentCode {

    EMPTY("00", ""),
    SUCCESS("01", "SUCCESS"),
    OK("02", "OK"),
    L_S_M("03", "L S M"),
    L_S("04", "L S"),
    COUNT("05", "C"),
    F_S_M("06", "F S M"),
    X_M("07", "X M"),
    TIMESTAMP("08", "N"),
    DATE("09", "D"),
    TIME("10", "T"),
    LINES("11", "L"),
    SIZE("12", "S"),
    MD5("13", "M"),
    ;

    private final String code;
    private final String template;

    ContentCode(String code, String template) {
        this.code = code;
        this.template = template;
    }

    private static final Map<String, ContentCode> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(c -> c.code, c -> c));

    /**
     * 按编号查找。找不到时返回 null（非异常，由调用方决定默认行为）。
     */
    public static ContentCode fromCode(String code) {
        return BY_CODE.get(code);
    }

    /**
     * 是否为空内容编号（00）。
     */
    public static boolean isEmpty(String code) {
        return "00".equals(code) || code == null || code.isEmpty();
    }
}