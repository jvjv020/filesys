package com.fmsy.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析器配置(JSON)解析工具
 *
 * 功能说明：
 * - 将 transfer_config.parser_config(JSON字符串)解析为 Map<String,String>
 * - 不依赖外部 JSON 库,采用最小手写解析器
 * - 支持: "key":"value", "key":123, "key":true, "key":false, "key":null
 * - 不支持嵌套对象/数组(本场景无此需求)
 *
 * 使用场景：
 * - 转换器(CSV/DBF/XML/TXT)在 parse/generate 时读取自定义配置
 * - 字段映射构建器读取 default config 覆盖值
 */
public final class ParserConfigUtil {

    private ParserConfigUtil() {
    }

    /**
     * 解析 JSON 字符串为键值对。
     *
     * @param json JSON 字符串,可能为 null
     * @return 解析结果,若 json 为 null/空白/解析失败则返回空 Map
     */
    public static Map<String, String> parseJson(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null) {
            return result;
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty()) {
            return result;
        }
        // 包裹在 {...} 之内,允许 { } 包围或裸键值列表
        String body = trimmed;
        if (body.charAt(0) == '{') {
            body = body.substring(1);
        }
        if (body.charAt(body.length() - 1) == '}') {
            body = body.substring(0, body.length() - 1);
        }

        int i = 0;
        int n = body.length();
        while (i < n) {
            // 跳过空白与逗号
            while (i < n && (Character.isWhitespace(body.charAt(i)) || body.charAt(i) == ',')) {
                i++;
            }
            if (i >= n) {
                break;
            }
            // 期望 "key"
            if (body.charAt(i) != '"') {
                // 非法格式,放弃剩余内容
                break;
            }
            int keyStart = ++i;
            while (i < n && body.charAt(i) != '"') {
                if (body.charAt(i) == '\\' && i + 1 < n) {
                    i += 2;
                } else {
                    i++;
                }
            }
            if (i >= n) {
                break;
            }
            String key = unescape(body.substring(keyStart, i));
            i++; // skip closing quote
            // 跳过空白,期望 :
            while (i < n && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
            if (i >= n || body.charAt(i) != ':') {
                break;
            }
            i++;
            while (i < n && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            String value;
            char c = body.charAt(i);
            if (c == '"') {
                int valStart = ++i;
                while (i < n && body.charAt(i) != '"') {
                    if (body.charAt(i) == '\\' && i + 1 < n) {
                        i += 2;
                    } else {
                        i++;
                    }
                }
                if (i >= n) {
                    break;
                }
                value = unescape(body.substring(valStart, i));
                i++;
            } else {
                // 字面量(数字/true/false/null),读取到逗号/空白/右大括号为止
                int valStart = i;
                while (i < n && body.charAt(i) != ',' && body.charAt(i) != '}'
                        && !Character.isWhitespace(body.charAt(i))) {
                    i++;
                }
                value = body.substring(valStart, i);
            }
            result.put(key, value);
        }
        return result;
    }

    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(i + 1);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    default -> sb.append(n);
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
