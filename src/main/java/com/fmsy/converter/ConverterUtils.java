package com.fmsy.converter;

import com.fmsy.model.FieldMapping;
import com.fmsy.util.ParserConfigUtil;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 转换器共用辅助方法
 *
 * 消除 Csv/Txt/Xml/Dbf 4 个 converter 中 4× 重复的 config 合并、
 * charset/int/unescape 解析逻辑。包私有,不暴露给 converter 包以外。
 */
final class ConverterUtils {

    private ConverterUtils() {
    }

    /** 把 converter 默认配置与 mapping.config.parserConfig(JSON)合并,返回新 Map */
    static Map<String, String> mergeConfig(Map<String, String> defaultConfig, FieldMapping mapping) {
        Map<String, String> cfg = new HashMap<>(defaultConfig);
        if (mapping != null && mapping.getConfig() != null) {
            cfg.putAll(ParserConfigUtil.parseJson(mapping.getConfig().getParserConfig()));
        }
        return cfg;
    }

    /** 解析 charset 名称,失败回退 UTF-8 */
    static Charset resolveCharset(String name) {
        return resolveCharset(name, Charset.forName("UTF-8"));
    }

    /** 解析 charset 名称,失败回退 defaultCharset(DBF 用 GBK) */
    static Charset resolveCharset(String name, Charset defaultCharset) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return defaultCharset;
        }
    }

    /** 安全解析整数,失败回退 def */
    static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    /** 解析 JSON 数组格式的字段列表,每元素为 {...} 平对象 */
    static List<Map<String, String>> parseJsonArray(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        if (json == null) {
            return result;
        }
        int i = 0;
        while (i < json.length() && json.charAt(i) != '[') {
            i++;
        }
        if (i >= json.length()) {
            return result;
        }
        i++;
        while (i < json.length()) {
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i >= json.length() || json.charAt(i) == ']') {
                break;
            }
            if (json.charAt(i) == ',') {
                i++;
                continue;
            }
            if (json.charAt(i) == '{') {
                int start = i;
                int depth = 0;
                while (i < json.length()) {
                    char c = json.charAt(i);
                    if (c == '"') {
                        i++;
                        while (i < json.length() && json.charAt(i) != '"') {
                            if (json.charAt(i) == '\\') i++;
                            i++;
                        }
                    } else if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            i++;
                            break;
                        }
                    }
                    i++;
                }
                String block = json.substring(start, i);
                result.add(ParserConfigUtil.parseJson(block));
            } else {
                i++;
            }
        }
        return result;
    }

    /** 把 JSON 字符串中的常见转义还原为字面字符(\t \n \r) */
    static String unescapeValue(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        return s.replace("\\t", "\t").replace("\\n", "\n").replace("\\r", "\r");
    }
}
