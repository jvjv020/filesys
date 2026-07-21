package com.fmsy.transfer.download.multi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PK 范围工具类 — 在分桶切分（{@link MultiNodeFlowService}）和子节点桶处理
 * （{@link ChildBucketProcessor}）之间共享 PK 值序列化/反序列化逻辑。
 *
 * <p>specName 格式为 {@code "partitionName|pkStart|pkEnd"}，其中 pkStart/pkEnd 是
 * 逗号分隔的 PK 列值字符串。本类提供双向转换：
 * <ul>
 *   <li>{@link #formatPkValues} — {@code List<Object>} → 逗号字符串（写入 specName）</li>
 *   <li>{@link #parsePkValues} — 逗号字符串 → {@code List<Object>}（从 specName 还原）</li>
 *   <li>{@link #extractPkValuesList} — 从查询结果 Map 提取 PK 列值列表</li>
 * </ul>
 *
 * <p>无状态静态方法，线程安全。
 */
public class PkRangeUtils {

    /** 从查询结果 Map 提取 PK 列值列表 */
    public static List<Object> extractPkValuesList(Map<String, Object> row, List<String> pkColumns) {
        List<Object> values = new ArrayList<>();
        for (String col : pkColumns) {
            values.add(row.get(col));
        }
        return values;
    }

    /** PK 值列表 → 逗号拼接字符串（用于 specName） */
    public static String formatPkValues(List<Object> pkValues) {
        return pkValues.stream()
                .map(v -> v != null ? v.toString() : "")
                .collect(Collectors.joining(","));
    }

    /**
     * 将 PK 值的逗号串（如 "100,200"）解析为 Object 列表。
     * 尝试数值类型推断（Long / Double），失败时保留字符串（UUID 等）。
     */
    public static List<Object> parsePkValues(String raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        String[] vals = raw.split(",", -1);
        List<Object> result = new ArrayList<>(vals.length);
        for (String v : vals) {
            String t = v.trim();
            if (t.isEmpty()) { result.add(null); continue; }
            try {
                result.add(t.contains(".") ? (Object) Double.parseDouble(t) : Long.parseLong(t));
            } catch (NumberFormatException e) {
                result.add(t); // 字符串 PK（UUID 等）
            }
        }
        return result;
    }

    private PkRangeUtils() {
        // 工具类，禁止实例化
    }
}