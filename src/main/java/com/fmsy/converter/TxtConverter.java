package com.fmsy.converter;

import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * TXT文件转换器
 *
 * 功能说明：
 * - 解析分隔符或定长TXT文件（默认 "|" 分隔）
 * - 生成TXT格式输出
 * - 流式解析避免内存溢出
 * - 支持外部字段规格文件 config/txt/{类别}_{控制}.fields.json
 *
 * 解析配置参数(parserConfig JSON)：
 * - encoding: 文件编码,默认 UTF-8
 * - separator: 分隔符,默认 "|"
 * - mode: "delimiter" | "fixed",默认 "delimiter"
 * - fieldWidths: 字段宽度列表(fixed模式),如 "10,20,15"
 * - skipLines: 跳过行数,默认 0
 */
@Slf4j
public class TxtConverter implements FileConverter {

    @Override
    public Iterator<List<Map<String, Object>>> parse(InputStream input, FieldMapping mapping) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        Charset encoding = ConverterUtils.resolveCharset(cfg.getOrDefault("encoding", "UTF-8"));
        String mode = cfg.getOrDefault("mode", "delimiter");
        String separator = ConverterUtils.unescapeValue(cfg.getOrDefault("separator", "|"));
        int skipLines = ConverterUtils.parseInt(cfg.getOrDefault("skipLines", "0"), 0);
        List<TxtFieldSpec> fieldSpecs = resolveFieldSpecs(mapping);
        if (!fieldSpecs.isEmpty()) {
            return new TxtIterator(input, encoding, separator, fieldSpecs);
        }
        String fieldWidths = cfg.get("fieldWidths");
        return new TxtIterator(input, encoding, mode, separator, fieldWidths, skipLines, mapping);
    }

    @Override
    public void generate(OutputStream output, Iterator<List<Map<String, Object>>> data, FieldMapping mapping) {
        writeHeader(output, mapping, 0);
        writeDataRecords(output, data, mapping);
        // TXT 无 footer
    }

    @Override
    public void writeHeader(OutputStream output, FieldMapping mapping, long recordCount) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        Charset encoding = ConverterUtils.resolveCharset(cfg.getOrDefault("encoding", "UTF-8"));
        String separator = ConverterUtils.unescapeValue(cfg.getOrDefault("separator", "|"));
        try {
            List<String> fields = mapping.getTableFields();
            output.write(String.join(separator, fields).getBytes(encoding));
            output.write('\n');
        } catch (IOException e) {
            throw new RuntimeException("Failed to write TXT header", e);
        }
    }

    @Override
    public int writeDataRecords(OutputStream output, Iterator<List<Map<String, Object>>> data, FieldMapping mapping) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        Charset encoding = ConverterUtils.resolveCharset(cfg.getOrDefault("encoding", "UTF-8"));
        String mode = cfg.getOrDefault("mode", "delimiter");
        String separator = ConverterUtils.unescapeValue(cfg.getOrDefault("separator", "|"));
        boolean fixed = "fixed".equalsIgnoreCase(mode);
        List<TxtFieldSpec> fieldSpecs = resolveFieldSpecs(mapping);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, encoding))) {
            List<String> fields = mapping.getTableFields();
            int recordCount = 0;
            while (data.hasNext()) {
                List<Map<String, Object>> batch = data.next();
                for (Map<String, Object> row : batch) {
                    if (fixed && !fieldSpecs.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (TxtFieldSpec spec : fieldSpecs) {
                            Object value = mapping.getValue(row, spec.name);
                            sb.append(padToWidth(value != null ? value.toString() : "", spec.length));
                        }
                        writer.write(sb.toString());
                    } else {
                        List<String> values = new ArrayList<>();
                        for (String field : fields) {
                            Object value = mapping.getValue(row, field);
                            values.add(value != null ? value.toString() : "");
                        }
                        writer.write(String.join(separator, values));
                    }
                    writer.newLine();
                    recordCount++;
                }
            }
            writer.flush();
            log.info("Generated TXT with {} records", recordCount);
            return recordCount;
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate TXT", e);
        }
    }

    @Override
    public int countRecords(InputStream input, FieldMapping mapping) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        Charset encoding = ConverterUtils.resolveCharset(cfg.getOrDefault("encoding", "UTF-8"));
        int skipLines = ConverterUtils.parseInt(cfg.getOrDefault("skipLines", "0"), 0);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, encoding))) {
            reader.readLine();
            for (int i = 1; i < skipLines; i++) reader.readLine();
            int count = 0;
            while (reader.readLine() != null) count++;
            log.info("TXT countRecords: {} lines", count);
            return count;
        } catch (IOException e) {
            log.warn("Failed to count TXT records: {}", e.getMessage());
            return -1;
        }
    }

    private List<TxtFieldSpec> resolveFieldSpecs(FieldMapping mapping) {
        TransferConfig config = mapping != null ? mapping.getConfig() : null;
        if (config == null) return List.of();
        String cat = config.getCategoryCode();
        String ctrl = config.getControlCode();
        if (cat == null || ctrl == null) return List.of();
        Path path = Paths.get("config", "txt", cat + "_" + ctrl + ".fields.json");
        if (!Files.exists(path)) return List.of();
        try {
            String content = Files.readString(path);
            List<Map<String, String>> items = ConverterUtils.parseJsonArray(content);
            List<TxtFieldSpec> specs = new ArrayList<>();
            for (Map<String, String> item : items) {
                String name = item.get("name");
                if (name == null || name.isBlank()) continue;
                int length = ConverterUtils.parseInt(item.get("length"), 0);
                String type = item.getOrDefault("type", "string");
                boolean required = Boolean.parseBoolean(item.getOrDefault("required", "false"));
                specs.add(new TxtFieldSpec(name, length, type, required));
            }
            log.info("Loaded TXT field specs from {}: {} field(s)", path, specs.size());
            return specs;
        } catch (IOException e) {
            log.warn("Failed to read TXT fields config {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    /** 按指定宽度补空格(右对齐/截断)。预留给定长模式使用。 */
    private String padToWidth(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        StringBuilder sb = new StringBuilder(width);
        for (int i = 0; i < width - s.length(); i++) sb.append(' ');
        return s + sb;
    }

    @Override
    public String getFormat() {
        return "TXT";
    }

    @Override
    public Map<String, String> getDefaultConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("encoding", "UTF-8");
        config.put("separator", "|");
        config.put("mode", "delimiter");
        config.put("skipLines", "0");
        return config;
    }

    private record TxtFieldSpec(String name, int length, String type, boolean required) {
    }

    private static Object defaultValueFor(String type) {
        return switch (type) {
            case "int", "long", "number" -> 0;
            case "double", "float" -> 0.0;
            case "bool", "boolean" -> false;
            default -> "";
        };
    }

    private static List<Integer> parseFieldWidths(String csv) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        List<Integer> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                list.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
                // 忽略非法宽度
            }
        }
        return list;
    }

    /**
     * TXT流式迭代器
     * 流式读取TXT文件，每次返回一批记录
     */
    private static class TxtIterator implements Iterator<List<Map<String, Object>>>, AutoCloseable {
        private final BufferedReader reader;
        private final List<String> headers;
        private final boolean fixed;
        private final String separator;
        private final List<Integer> fieldWidths;
        private final List<TxtFieldSpec> fieldSpecs;
        private String[] nextLine;
        private boolean hasNext = true;
        private static final int BATCH_SIZE = 1000;

        TxtIterator(InputStream input, Charset encoding, String separator,
                    List<TxtFieldSpec> fieldSpecs) {
            this.reader = new BufferedReader(new InputStreamReader(input, encoding));
            this.headers = new ArrayList<>();
            this.separator = separator;
            this.fixed = false;
            this.fieldWidths = List.of();
            this.fieldSpecs = fieldSpecs;
            for (TxtFieldSpec spec : fieldSpecs) {
                headers.add(spec.name);
            }
            try {
                String line = reader.readLine();
                nextLine = line != null ? line.split(java.util.regex.Pattern.quote(separator)) : null;
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse TXT", e);
            }
        }

        TxtIterator(InputStream input, Charset encoding, String mode, String separator,
                    String fieldWidthsCsv, int skipLines, FieldMapping mapping) {
            this.reader = new BufferedReader(new InputStreamReader(input, encoding));
            this.headers = new ArrayList<>();
            this.separator = separator;
            this.fieldWidths = parseFieldWidths(fieldWidthsCsv);
            this.fieldSpecs = List.of();
            this.fixed = "fixed".equalsIgnoreCase(mode) && !this.fieldWidths.isEmpty();
            try {
                if (fixed) {
                    // 定长模式: 优先使用 mapping.tableFields 作为 headers
                    if (mapping != null && mapping.getTableFields() != null && !mapping.getTableFields().isEmpty()) {
                        headers.addAll(mapping.getTableFields());
                    } else {
                        // fallback: 自动生成 COL1..COLn
                        for (int i = 0; i < fieldWidths.size(); i++) {
                            headers.add("COL" + (i + 1));
                        }
                    }
                    String line = reader.readLine();
                    if (line != null) {
                        nextLine = splitFixed(line);
                    } else {
                        nextLine = null;
                    }
                } else {
                    int consumed = 0;
                    String headerLine = null;
                    while (consumed < skipLines) {
                        String l = reader.readLine();
                        if (l == null) {
                            break;
                        }
                        consumed++;
                        if (consumed == skipLines) {
                            headerLine = l;
                        }
                    }
                    if (headerLine == null) {
                        headerLine = reader.readLine();
                    }
                    if (headerLine != null) {
                        headers.addAll(Arrays.asList(headerLine.split(java.util.regex.Pattern.quote(separator))));
                    }
                    String dataLine = reader.readLine();
                    nextLine = dataLine != null ? dataLine.split(java.util.regex.Pattern.quote(separator)) : null;
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse TXT", e);
            }
        }

        private String[] splitFixed(String line) {
            String[] arr = new String[fieldWidths.size()];
            int offset = 0;
            for (int i = 0; i < fieldWidths.size(); i++) {
                int w = fieldWidths.get(i);
                if (offset >= line.length()) {
                    arr[i] = "";
                } else if (offset + w > line.length()) {
                    arr[i] = line.substring(offset).trim();
                } else {
                    arr[i] = line.substring(offset, offset + w).trim();
                }
                offset += w;
            }
            return arr;
        }

        @Override
        public void close() throws IOException {
            if (reader != null) {
                reader.close();
            }
        }

        @Override
        public boolean hasNext() {
            return hasNext && nextLine != null;
        }

        /** 返回一批记录 */
        @Override
        public List<Map<String, Object>> next() {
            List<Map<String, Object>> batch = new ArrayList<>();
            if (!hasNext || nextLine == null) return batch;

            try {
                int count = 0;
                while (count < BATCH_SIZE && nextLine != null) {
                    Map<String, Object> record = new HashMap<>();
                    for (int i = 0; i < headers.size() && i < nextLine.length; i++) {
                        String rawValue = nextLine[i];
                        if (!fieldSpecs.isEmpty() && i < fieldSpecs.size()) {
                            TxtFieldSpec spec = fieldSpecs.get(i);
                            rawValue = validateAndFill(spec, rawValue);
                        }
                        record.put(headers.get(i), rawValue);
                    }
                    batch.add(record);
                    count++;
                    String line = reader.readLine();
                    nextLine = line != null
                            ? (fixed ? splitFixed(line) : line.split(java.util.regex.Pattern.quote(separator)))
                            : null;
                    if (nextLine == null) {
                        hasNext = false;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse TXT", e);
            }
            return batch;
        }

        private String validateAndFill(TxtFieldSpec spec, String value) {
            if (value == null) value = "";
            if (value.length() > spec.length && spec.length > 0) {
                log.warn("TXT field '{}' value '{}' exceeds length {}, truncated", spec.name, value, spec.length);
                return value.substring(0, spec.length);
            }
            if (value.isEmpty() && !spec.required) {
                return String.valueOf(defaultValueFor(spec.type));
            }
            return value;
        }
    }
}