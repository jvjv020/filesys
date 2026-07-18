package com.fmsy.converter;

import com.fmsy.model.FieldMapping;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

/**
 * CSV文件转换器
 *
 * 功能说明：
 * - 解析CSV文件（逗号分隔，UTF-8编码）
 * - 生成CSV格式输出
 * - 流式解析避免内存溢出
 * - 默认不写字段行,通过 header=true 开启
 *
 * 解析配置参数(parserConfig JSON)：
 * - encoding: 文件编码,默认 UTF-8
 * - separator: 分隔符,默认 ","
 * - quote: 引用符,默认 "\""
 * - header: 首行是否为字段名,默认 false
 * - skipLines: 跳过行数,默认 0
 * - headers: 显式表头列表(JSON 数组格式,可覆盖文件内表头)
 */
@Slf4j
public class CsvConverter implements FileConverter {

    @Override
    public Iterator<List<Map<String, Object>>> parse(InputStream input, FieldMapping mapping) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        Charset encoding = ConverterUtils.resolveCharset(cfg.getOrDefault("encoding", "UTF-8"));
        int skipLines = ConverterUtils.parseInt(cfg.getOrDefault("skipLines", "0"), 0);
        boolean header = Boolean.parseBoolean(cfg.getOrDefault("header", "false"));
        String separator = ConverterUtils.unescapeValue(cfg.getOrDefault("separator", ","));
        String quote = ConverterUtils.unescapeValue(cfg.getOrDefault("quote", "\""));
        String overrideHeaders = cfg.get("headers");
        return new CsvIterator(input, encoding, skipLines, header, separator, quote, overrideHeaders, mapping);
    }

    @Override
    public void generate(OutputStream output, Iterator<List<Map<String, Object>>> data, FieldMapping mapping) {
        writeHeader(output, mapping, 0);
        writeDataRecords(output, data, mapping);
        // CSV 无 footer
    }

    @Override
    public void writeHeader(OutputStream output, FieldMapping mapping, long recordCount) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        Charset encoding = ConverterUtils.resolveCharset(cfg.getOrDefault("encoding", "UTF-8"));
        String separator = ConverterUtils.unescapeValue(cfg.getOrDefault("separator", ","));
        boolean header = Boolean.parseBoolean(cfg.getOrDefault("header", "false"));
        if (!header) return;
        try {
            List<String> fields = mapping.getTableFields();
            output.write(String.join(separator, fields).getBytes(encoding));
            output.write('\n');
        } catch (IOException e) {
            throw new RuntimeException("Failed to write CSV header", e);
        }
    }

    @Override
    public int writeDataRecords(OutputStream output, Iterator<List<Map<String, Object>>> data, FieldMapping mapping) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        Charset encoding = ConverterUtils.resolveCharset(cfg.getOrDefault("encoding", "UTF-8"));
        String separator = ConverterUtils.unescapeValue(cfg.getOrDefault("separator", ","));
        String quote = ConverterUtils.unescapeValue(cfg.getOrDefault("quote", "\""));
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, encoding))) {
            List<String> fields = mapping.getTableFields();
            int recordCount = 0;
            while (data.hasNext()) {
                List<Map<String, Object>> batch = data.next();
                for (Map<String, Object> record : batch) {
                    List<String> values = new ArrayList<>();
                    for (String field : fields) {
                        Object value = mapping.getValue(record, field);
                        String strValue = value != null ? value.toString() : "";
                        if (strValue.contains(separator) || strValue.contains(quote) || strValue.contains("\n")) {
                            strValue = quote + strValue.replace(quote, quote + quote) + quote;
                        }
                        values.add(strValue);
                    }
                    writer.write(String.join(separator, values));
                    writer.newLine();
                    recordCount++;
                }
            }
            writer.flush();
            String table = mapping != null && mapping.getConfig() != null ? mapping.getConfig().getTableName() : null;
            log.info("Generated CSV with {} records{}", recordCount, table != null ? " for table " + table : "");
            return recordCount;
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate CSV", e);
        }
    }

    @Override
    public String getFormat() {
        return "CSV";
    }

    @Override
    public Map<String, String> getDefaultConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("encoding", "UTF-8");
        config.put("separator", ",");
        config.put("quote", "\"");
        config.put("skipLines", "0");
        config.put("header", "false");
        return config;
    }

    /**
     * CSV流式迭代器
     * 流式读取CSV文件，每次返回一批记录
     */
    private static class CsvIterator implements Iterator<List<Map<String, Object>>>, AutoCloseable {
        private final BufferedReader reader;
        private final List<String> headers;
        private final String separator;
        private final String quote;
        private List<String> nextLine;
        private boolean hasNext = true;
        private boolean closed = false;
        private static final int BATCH_SIZE = 1000;

        CsvIterator(InputStream input, Charset encoding, int skipLines, boolean header,
                    String separator, String quote, String overrideHeaders, FieldMapping mapping) {
            this.reader = new BufferedReader(new InputStreamReader(input, encoding));
            this.headers = new ArrayList<>();
            // P1#5:由调用方按 parserConfig 注入,不再硬编码;空值回退到 ","
            this.separator = (separator == null || separator.isEmpty()) ? "," : separator;
            this.quote = (quote == null || quote.isEmpty()) ? "\"" : quote;
            try {
                if (overrideHeaders != null && !overrideHeaders.isEmpty()) {
                    headers.addAll(parseHeaders(overrideHeaders));
                } else if (header) {
                    for (int i = 0; i < skipLines; i++) {
                        String line = reader.readLine();
                        if (line == null) {
                            hasNext = false;
                            break;
                        }
                    }
                    String headerLine = reader.readLine();
                    if (headerLine != null) {
                        headers.addAll(parseLine(headerLine));
                    }
                } else if (mapping != null && mapping.getTableFields() != null) {
                    headers.addAll(mapping.getTableFields());
                    for (int i = 0; i < skipLines; i++) {
                        if (reader.readLine() == null) {
                            hasNext = false;
                            break;
                        }
                    }
                }
                String firstLine = reader.readLine();
                nextLine = firstLine != null ? parseLine(firstLine) : null;
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse CSV", e);
            }
        }

        @Override
        public void close() {
            if (!closed) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
                closed = true;
            }
        }

        private static List<String> parseHeaders(String raw) {
            List<String> result = new ArrayList<>();
            String h = raw.trim();
            if (h.startsWith("[") && h.endsWith("]")) {
                h = h.substring(1, h.length() - 1);
            }
            for (String tok : h.split(",")) {
                String t = tok.trim();
                if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
                    t = t.substring(1, t.length() - 1);
                }
                if (!t.isEmpty()) {
                    result.add(t);
                }
            }
            return result;
        }

        /** 解析一行CSV（处理引号转义,P1#5:按配置的 separator/quote 解析） */
        private List<String> parseLine(String line) {
            List<String> tokens = new ArrayList<>();
            boolean inQuotes = false;
            StringBuilder token = new StringBuilder();
            char quoteChar = quote.isEmpty() ? '"' : quote.charAt(0);
            char separatorChar = separator.isEmpty() ? ',' : separator.charAt(0);
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == quoteChar) {
                    inQuotes = !inQuotes;
                } else if (c == separatorChar && !inQuotes) {
                    tokens.add(token.toString().trim());
                    token = new StringBuilder();
                } else {
                    token.append(c);
                }
            }
            tokens.add(token.toString().trim());
            return tokens;
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
                    for (int i = 0; i < headers.size() && i < nextLine.size(); i++) {
                        record.put(headers.get(i), nextLine.get(i));
                    }
                    batch.add(record);
                    count++;
                    String line = reader.readLine();
                    nextLine = line != null ? parseLine(line) : null;
                    if (nextLine == null) {
                        hasNext = false;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse CSV", e);
            }
            return batch;
        }
    }
}