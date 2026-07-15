package com.fmsy.transfer;

import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.db.TableMetadataService;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ParserConfigUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段映射构建器
 *
 * 功能说明(对应需求规格 9.2.1 节):
 * - 上传映射(文件→数据库):
 *   1. 读目标表元数据
 *   2. 排除 ignoreFields
 *   3. tableFields = 剩余字段(extraFields 中的字段也保留在 tableFields 中,
 *      写库时通过 FieldMapping.getValue 回退到 extraFields 取值)
 *   4. extraFields = detail.fieldName→fieldValue 拆分(固定值,文件不提供该列)
 * - 下发映射(数据库→文件):
 *   1. 读源表元数据
 *   2. 排除 ignoreFields
 *   3. tableFields = 剩余字段
 *   4. extraFields = null
 *
 * parserConfig 覆盖:
 * - 在 build 时通过 converter.getDefaultConfig() 取得默认值
 * - ParserConfigUtil.parseJson(config.parserConfig) 覆盖到 defaultConfig
 * - 转换器在 parse/generate 时读取 (mapping.getConfig().getParserConfig() 已经被填充)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FieldMappingBuilder {

    private final TableMetadataService tableMetadataService;
    private final ConverterFactory converterFactory;

    /**
     * 构建上传场景的 FieldMapping（仅基础部分，不含 extraFields）。
     *
     * <p>与 {@link #buildForUpload(TransferConfig, Map)} 的区别在于不处理 detail 的 extraFields，
     * 适用于 BATCH 模式中所有明细共享同一张表、仅 extraFields 不同的场景。
     * 调用方先构建一次 base，再对每个明细调用 {@link #attachExtraFields(FieldMapping, Map)} 附加 extras。
     *
     * <p>步骤:
     * <ol>
     *   <li>读目标表元数据作为 base 字段列表</li>
     *   <li>排除 {@code ignoreFields}</li>
     *   <li>extraFields 保持 null</li>
     * </ol>
     *
     * @param config 传输配置
     * @return 不含 extraFields 的 FieldMapping
     */
    public FieldMapping buildForUploadBase(TransferConfig config) {
        if (config == null) {
            FieldMapping mapping = new FieldMapping();
            mapping.setConfig(null);
            return mapping;
        }
        FieldMapping mapping = new FieldMapping();
        mapping.setConfig(applyParserConfig(config));
        List<String> columns = tableMetadataService.getTableColumns(config.getDbName(), config.getTableName());
        columns = excludeFields(columns, config.getIgnoreFields());
        mapping.setTableFields(new ArrayList<>(columns));
        log.debug("buildForUploadBase: db={}, table={}, columns={}",
                config.getDbName(), config.getTableName(), columns);
        return mapping;
    }

    /**
     * 向基础 FieldMapping 附加 extraFields（从明细行解析）。
     *
     * <p>返回新的 FieldMapping 对象，不修改传入的 base 映射（线程安全）。
     * 若 detail 为 null 或不含 extraFields，直接返回 base 本身。
     *
     * @param base   基础映射（由 {@link #buildForUploadBase} 或 {@link #buildForUpload} 构建）
     * @param detail 明细行（可空；为 null 时返回 base）
     * @return 含 extraFields 的新 FieldMapping（或 base 本身）
     */
    public FieldMapping attachExtraFields(FieldMapping base, Map<String, Object> detail) {
        if (detail == null) {
            return base;
        }
        Map<String, String> extras = extractExtraFields(detail);
        if (extras.isEmpty()) {
            return base;
        }
        // 克隆 base 并附加 extras（不修改原对象，线程安全）
        FieldMapping copy = new FieldMapping();
        copy.setConfig(base.getConfig());
        copy.setTableFields(new ArrayList<>(base.getTableFields()));
        copy.setExtraFields(extras);
        return copy;
    }

    /**
     * 构建上传场景的完整 FieldMapping（含 extraFields）。
     *
     * <p>内部先调用 {@link #buildForUploadBase} 构建基础部分，再根据 detail 附加 extraFields。
     * 若 detail 为 null，等效于 {@code buildForUploadBase(config)}。</p>
     *
     * @param config 传输配置
     * @param detail 明细行（可空；为 null 时不处理 extraFields）
     * @return 完整 FieldMapping
     */
    public FieldMapping buildForUpload(TransferConfig config, Map<String, Object> detail) {
        FieldMapping base = buildForUploadBase(config);
        return attachExtraFields(base, detail);
    }

    /**
     * 构建下发场景的 FieldMapping。
     */
    public FieldMapping buildForDownload(TransferConfig config) {
        if (config == null) {
            FieldMapping mapping = new FieldMapping();
            mapping.setConfig(null);
            return mapping;
        }
        FieldMapping mapping = new FieldMapping();
        mapping.setConfig(applyParserConfig(config));
        List<String> columns = tableMetadataService.getTableColumns(config.getDbName(), config.getTableName());
        columns = excludeFields(columns, config.getIgnoreFields());
        mapping.setTableFields(new ArrayList<>(columns));
        // extraFields: 下发场景与 detail 无关,保持 null
        log.debug("buildForDownload: db={}, table={}, columns={}",
                config.getDbName(), config.getTableName(), columns);
        return mapping;
    }

    /**
     * 把 converter.getDefaultConfig() 与 config.parserConfig(JSON)合并:
     * - 先复制 default,再用 parserConfig 覆盖
     * - 始终返回非 null Map
     *
     * 转换器当前尚未迁移到读取 parserConfig,本方法作为预填字段保留:
     * 将合并后的 key=value 重新序列化回 TransferConfig.parserConfig(单行 JSON 形式),
     * 转换器即可从 config.getParserConfig() 获取完整覆盖值。
     */
    private TransferConfig applyParserConfig(TransferConfig config) {
        if (config == null) {
            return null;
        }
        FileConverter converter = converterFactory.get(config.getParserType());
        Map<String, String> merged = new LinkedHashMap<>(converter.getDefaultConfig());
        Map<String, String> override = ParserConfigUtil.parseJson(config.getParserConfig());
        if (!override.isEmpty()) {
            merged.putAll(override);
        }
        // 重新序列化(仅当与默认不同或用户已配置)
        if (!override.isEmpty() || config.getParserConfig() == null) {
            config.setParserConfig(serializeFlatJson(merged));
        }
        return config;
    }

    private static String serializeFlatJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append('"').append(':');
            sb.append('"').append(escapeJson(e.getValue())).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 从逗号分隔的字段列表中排除指定字段(忽略大小写)。返回新列表,保留原顺序。
     */
    private List<String> excludeFields(List<String> cols, String commaSepFields) {
        if (cols == null || cols.isEmpty()) {
            return new ArrayList<>();
        }
        if (commaSepFields == null || commaSepFields.isEmpty()) {
            return new ArrayList<>(cols);
        }
        List<String> excludes = new ArrayList<>();
        for (String s : commaSepFields.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                excludes.add(t.toLowerCase(java.util.Locale.ROOT));
            }
        }
        List<String> result = new ArrayList<>(cols.size());
        for (String c : cols) {
            if (c != null && !excludes.contains(c.toLowerCase(java.util.Locale.ROOT))) {
                result.add(c);
            }
        }
        return result;
    }

    private Map<String, String> extractExtraFields(Map<String, Object> detail) {
        Object namesObj = detail.get(ColumnNames.FIELD_NAME);
        Object valuesObj = detail.get(ColumnNames.FIELD_VALUE);
        if (namesObj == null || valuesObj == null) {
            return new LinkedHashMap<>();
        }
        return TransferSupport.splitFieldValues(String.valueOf(namesObj), String.valueOf(valuesObj));
    }
}