package com.fmsy.transfer;

import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TargetTableRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 拆分字段工具 — 统一处理 splitFields 逗号分隔字符串的解析、谓词构建、DISTINCT 查询。
 *
 * <p>集中管理以下分散在各类的重复逻辑：
 * <ul>
 *   <li>{@link BucketDistributor#distinctBuckets} + {@code SplitFlowService.queryDistinctSplitValues} — DISTINCT 分桶值查询</li>
 *   <li>{@code SplitFlowService.buildSplitWhere} + {@code SplitFlowService.buildSplitParams} — 拆分字段 WHERE 条件</li>
 *   <li>{@code TargetTableRepository.buildBucketPredicates} — 桶谓词构建</li>
 *   <li>{@link TransferSupport#splitFieldValues} — 字段名/值 Map 解析</li>
 * </ul>
 *
 * <p>所有方法均为 static，无状态，可直接调用。
 */
public final class SplitFieldHelper {

    private SplitFieldHelper() {
    }

    // ==================== 字段名解析 ====================

    /**
     * 将逗号分隔的 splitFields 字符串解析为字段名数组（已 trim）。
     *
     * @param splitFields 逗号分隔的字段名，如 "REGION,STATUS"
     * @return 字段名数组；null/空输入返回空数组
     */
    public static String[] splitFieldNames(String splitFields) {
        if (splitFields == null || splitFields.isEmpty()) {
            return new String[0];
        }
        String[] raw = splitFields.split(",");
        String[] result = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            result[i] = raw[i].trim();
        }
        return result;
    }

    // ==================== 谓词构建 ====================

    /**
     * 构建 "col1 = ? AND col2 = ?" 形式的 WHERE 子句字符串。
     * <p>对应旧 {@code SplitFlowService.buildSplitWhere}。</p>
     *
     * @param splitFields 逗号分隔的字段名
     * @param fieldValue  逗号分隔的字段值
     * @return "col1 = ? AND col2 = ?" 字符串
     */
    public static String buildWhereClause(String splitFields, String fieldValue) {
        String[] names = splitFieldNames(splitFields);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(" AND ");
            sb.append(names[i]).append(" = ?");
        }
        return sb.toString();
    }

    /**
     * 从 fieldValue 中提取参数值列表。
     * <p>对应旧 {@code SplitFlowService.buildSplitParams}。</p>
     *
     * @param splitFields 逗号分隔的字段名（仅用于确定数量）
     * @param fieldValue  逗号分隔的字段值
     * @return 参数值列表
     */
    public static List<Object> buildParams(String splitFields, String fieldValue) {
        String[] values = fieldValue.split(",");
        return Arrays.stream(values).map(String::trim).collect(Collectors.toList());
    }

    /**
     * 构建 "col = ?" 谓词列表并填充参数 — 对应旧 {@code TargetTableRepository.buildBucketPredicates}。
     *
     * @param splitFields 逗号分隔的字段名
     * @param fieldValue  逗号分隔的字段值
     * @param params      输出参数：追加绑定值到该列表
     * @return "col = ?" 谓词列表
     * @throws IllegalArgumentException 字段名/值数量不匹配时抛出
     */
    public static List<String> buildPredicates(String splitFields, String fieldValue, List<Object> params) {
        String[] fieldNames = splitFieldNames(splitFields);
        String[] fieldValues = fieldValue.split(",");
        if (fieldNames.length != fieldValues.length) {
            throw new IllegalArgumentException(
                    "splitField and fieldValue count mismatch: " + splitFields + " / " + fieldValue);
        }
        List<String> predicates = new ArrayList<>();
        for (int i = 0; i < fieldNames.length; i++) {
            String fieldName = fieldNames[i];
            if (fieldName.isEmpty()) {
                throw new IllegalArgumentException("splitField contains empty field: " + splitFields);
            }
            predicates.add(fieldName + " = ?");
            params.add(fieldValues[i].trim());
        }
        return predicates;
    }

    // ==================== 字段名/值 Map 解析 ====================

    /**
     * 将两个逗号分隔字符串按位置一一对应解析为 Map。
     * <p>对应旧 {@link TransferSupport#splitFieldValues}。</p>
     * <p>示例: splitFieldValues("REGION,STATUS", "EAST,ACTIVE") → {"REGION" -> "EAST", "STATUS" -> "ACTIVE"}</p>
     *
     * @param names  逗号分隔的字段名
     * @param values 逗号分隔的字段值
     * @return 字段名→字段值 Map；任一参数为 null/空时返回空 Map
     */
    public static Map<String, String> toMap(String names, String values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (names == null || names.isEmpty() || values == null || values.isEmpty()) {
            return result;
        }
        String[] nameArr = names.split(",");
        String[] valueArr = values.split(",");
        int len = Math.min(nameArr.length, valueArr.length);
        for (int i = 0; i < len; i++) {
            String n = nameArr[i].trim();
            String v = valueArr[i].trim();
            if (!n.isEmpty()) {
                result.put(n, v);
            }
        }
        return result;
    }

    // ==================== DISTINCT 分桶值查询 ====================

    /**
     * 从目标表查询 DISTINCT 分桶值，把多字段拼成 "v1,v2,..." 字符串。
     * <p>合并旧 {@link BucketDistributor#distinctBuckets} 和 {@code SplitFlowService.queryDistinctSplitValues} 的重复逻辑。</p>
     * <p>任一字段为 null 的行被丢弃(无法形成有效分桶)。</p>
     *
     * @param repository 目标表 Repository
     * @param config     传输配置（含 dbName, tableName, splitFields）
     * @return 分桶值列表(逗号分隔多字段)
     */
    public static List<String> queryDistinctBuckets(TargetTableRepository repository, TransferConfig config) {
        String[] fieldNames = splitFieldNames(config.getSplitFields());
        if (fieldNames.length == 0) return List.of();

        List<Map<String, Object>> distinctValues = repository.querySmallResult(
                config.getDbName(), config.getTableName(),
                Arrays.asList(fieldNames), true,
                null, null, Arrays.asList(fieldNames), null);

        List<String> bucketValues = new ArrayList<>();
        for (Map<String, Object> row : distinctValues) {
            StringBuilder fv = new StringBuilder();
            boolean allPresent = true;
            for (String name : fieldNames) {
                Object v = row.get(name);
                if (v == null) {
                    allPresent = false;
                    break;
                }
                if (fv.length() > 0) fv.append(',');
                fv.append(v);
            }
            if (allPresent) {
                bucketValues.add(fv.toString());
            }
        }
        return bucketValues;
    }
}