package com.fmsy.converter;

import com.fmsy.model.FieldMapping;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * DBF文件转换器
 *
 * 功能说明：
 * - 解析DBF文件格式，提取记录数据
 * - 生成符合DBF规范的输出文件
 *
 * 格式特点：
 * - 使用GBK编码（中文字段名和值）
 * - 流式解析避免内存溢出（返回迭代器）
 * - 字段长度按类型差异化:C(可配) / N(10) / D(8) / L(1)
 * - 内部类DbfIterator实现流式读取
 *
 * 解析配置参数(parserConfig JSON)：
 * - encoding: 文件编码,默认 GBK
 * - fieldTypes: 字段类型映射(JSON 对象),key=字段名,value=C/N/D/L
 *   例:{"AGE":"N","BIRTHDAY":"D","ACTIVE":"L"};未声明的字段默认 C
 * - charLength: 字符型字段长度,默认 10
 */
@Slf4j
public class DbfConverter implements FileConverter {

    private static final int HEADER_SIZE = 32;
    private static final int FIELD_DESC_SIZE = 32;
    private static final byte HEADER_TERMINATOR = 0x0D;
    private static final byte EOF_MARKER = 0x1A;
    private static final int DEFAULT_BATCH_SIZE = SystemConstants.DEFAULT_BATCH_SIZE;
    private static final Charset DEFAULT_ENCODING = Charset.forName("GBK");
    /** 字符型字段默认长度 */
    private static final int DEFAULT_CHAR_LENGTH = 10;
    /** 数值型字段长度 */
    private static final int NUMERIC_LENGTH = 10;
    /** 日期型字段长度(YYYYMMDD 8字节) */
    private static final int DATE_LENGTH = 8;
    /** 逻辑型字段长度(T/F/?) */
    private static final int LOGICAL_LENGTH = 1;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public Iterator<List<Map<String, Object>>> parse(InputStream input, FieldMapping mapping) {
        Charset encoding = resolveEncoding(mapping);
        return new DbfIterator(input, encoding);
    }

    @Override
    public void generate(OutputStream output, Iterator<List<Map<String, Object>>> data, FieldMapping mapping) {
        writeHeader(output, mapping, 0);
        int count = writeDataRecords(output, data, mapping);
        writeFooter(output, mapping);
        log.info("Generated DBF with {} records", count);
    }

    @Override
    public int countRecords(InputStream input, FieldMapping mapping) {
        try {
            input.skipNBytes(4);
            byte[] recordCountBytes = new byte[4];
            int read = input.read(recordCountBytes);
            if (read < 4) return -1;
            int count = (recordCountBytes[3] << 24) | ((recordCountBytes[2] & 0xFF) << 16)
                      | ((recordCountBytes[1] & 0xFF) << 8) | (recordCountBytes[0] & 0xFF);
            log.info("DBF countRecords: {} records (from header)", count);
            return count;
        } catch (IOException e) {
            log.warn("Failed to count DBF records: {}", e.getMessage());
            return -1;
        }
    }

    @Override
    public void writeHeader(OutputStream output, FieldMapping mapping, long recordCount) {
        Charset encoding = resolveEncoding(mapping);
        try {
            List<String> fields = mapping.getTableFields();
            Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
            Map<String, String> fieldTypes = parseFieldTypes(cfg.get("fieldTypes"));
            int charLength = ConverterUtils.parseInt(cfg.getOrDefault("charLength", String.valueOf(DEFAULT_CHAR_LENGTH)),
                    DEFAULT_CHAR_LENGTH);
            List<FieldSpec> fieldSpecs = buildFieldSpecs(fields, fieldTypes, charLength);
            writeHeaderWithRecordCount(output, fieldSpecs, encoding, recordCount);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write DBF header", e);
        }
    }

    @Override
    public int writeDataRecords(OutputStream output, Iterator<List<Map<String, Object>>> data, FieldMapping mapping) {
        Charset encoding = resolveEncoding(mapping);
        try {
            List<String> fields = mapping.getTableFields();
            Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
            Map<String, String> fieldTypes = parseFieldTypes(cfg.get("fieldTypes"));
            int charLength = ConverterUtils.parseInt(cfg.getOrDefault("charLength", String.valueOf(DEFAULT_CHAR_LENGTH)),
                    DEFAULT_CHAR_LENGTH);
            List<FieldSpec> fieldSpecs = buildFieldSpecs(fields, fieldTypes, charLength);
            return writeRecords(output, data, fieldSpecs, mapping, encoding);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write DBF records", e);
        }
    }

    @Override
    public void writeFooter(OutputStream output, FieldMapping mapping) {
        try {
            output.write(EOF_MARKER);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write DBF footer", e);
        }
    }

    private static Charset resolveEncoding(FieldMapping mapping) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        return ConverterUtils.resolveCharset(cfg.getOrDefault("encoding", "GBK"), DEFAULT_ENCODING);
    }

    /**
     * 解析字段类型映射。配置值形如 {@code {"AGE":"N","BIRTH":"D","ACTIVE":"L"}}
     * 由于 ParserConfigUtil 当前实现是 flat key-value,这里手工从原始 JSON 抽 fieldTypes 子对象。
     */
    private static Map<String, String> parseFieldTypes(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        // raw 形如 {"AGE":"N","BIRTHDAY":"D"} 或 {"AGE":"N", "BIRTHDAY":"D"}
        String s = raw.trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isEmpty()) return result;
        // 简单 CSV 解析,key/value 都用 " 包裹
        for (String pair : s.split(",")) {
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String key = pair.substring(0, colon).trim();
            String val = pair.substring(colon + 1).trim();
            if (key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            }
            if (val.startsWith("\"") && val.endsWith("\"")) {
                val = val.substring(1, val.length() - 1);
            }
            if (!key.isEmpty() && !val.isEmpty()) {
                result.put(key, val.toUpperCase());
            }
        }
        return result;
    }

    /**
     * 构建字段规范列表,决定每个字段的类型与字节长度。
     */
    private static List<FieldSpec> buildFieldSpecs(List<String> fields, Map<String, String> fieldTypes,
                                                    int charLength) {
        List<FieldSpec> specs = new ArrayList<>();
        for (String name : fields) {
            char type = 'C';
            int length = charLength;
            int decimal = 0;
            String configured = fieldTypes.get(name);
            if (configured != null) {
                switch (configured) {
                    case "N":
                        type = 'N';
                        length = NUMERIC_LENGTH;
                        decimal = 0;
                        break;
                    case "D":
                        type = 'D';
                        length = DATE_LENGTH;
                        break;
                    case "L":
                        type = 'L';
                        length = LOGICAL_LENGTH;
                        break;
                    case "C":
                    default:
                        type = 'C';
                        length = charLength;
                        break;
                }
            }
            specs.add(new FieldSpec(name, type, length, decimal));
        }
        return specs;
    }

    private static String summarizeTypes(List<FieldSpec> specs) {
        Map<Character, Integer> counts = new LinkedHashMap<>();
        for (FieldSpec s : specs) {
            counts.merge(s.type, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Character, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(e.getKey()).append("x").append(e.getValue());
        }
        return sb.toString();
    }

    /** 写DBF文件头 — 包含指定的记录数(用于并行模式预先填好) */
    private void writeHeaderWithRecordCount(OutputStream output, List<FieldSpec> fieldSpecs,
                                             Charset encoding, long recordCount) throws IOException {
        int headerSize = HEADER_SIZE + fieldSpecs.size() * FIELD_DESC_SIZE + 1;
        int recordLength = 1 + fieldSpecs.stream().mapToInt(s -> s.length).sum();

        output.write(0x03);
        Calendar cal = Calendar.getInstance();
        output.write(intToBcd(cal.get(Calendar.YEAR)));
        output.write(intToBcd(cal.get(Calendar.MONTH) + 1));
        output.write(intToBcd(cal.get(Calendar.DAY_OF_MONTH)));

        // bytes 4-7: 总记录数 (LE int32)
        output.write(intToLittleEndian((int) recordCount, 4));

        output.write(intToLittleEndian(headerSize, 2));
        output.write(intToLittleEndian(recordLength, 2));

        writeFieldDescriptors(output, fieldSpecs, encoding);

        output.write(HEADER_TERMINATOR);
    }


    /** 写字段描述符 - 严格32字节/字段,与readFieldDescriptors按32字节切片保持一致 */
    private void writeFieldDescriptors(OutputStream output, List<FieldSpec> fieldSpecs, Charset encoding)
            throws IOException {
        for (FieldSpec spec : fieldSpecs) {
            byte[] fieldDesc = new byte[FIELD_DESC_SIZE]; // 32字节全0
            byte[] fieldNameBytes = spec.name.getBytes(encoding);
            System.arraycopy(fieldNameBytes, 0, fieldDesc, 0, Math.min(fieldNameBytes.length, 10));
            fieldDesc[11] = (byte) spec.type;  // P2#1:按字段类型写 C/N/D/L
            fieldDesc[16] = (byte) spec.length; // 字段长度
            fieldDesc[17] = (byte) spec.decimal; // 小数位数(仅 N 类型有效)
            output.write(fieldDesc);
        }
    }

    /** 批量写记录 */
    private int writeRecords(OutputStream output, Iterator<List<Map<String, Object>>> data,
                           List<FieldSpec> fieldSpecs, FieldMapping mapping, Charset encoding) throws IOException {
        int recordCount = 0;
        while (data.hasNext()) {
            List<Map<String, Object>> batch = data.next();
            for (Map<String, Object> record : batch) {
                output.write(' ');
                for (FieldSpec spec : fieldSpecs) {
                    Object value = mapping.getValue(record, spec.name);
                    writeField(output, value, spec, encoding);
                }
                recordCount++;
            }
        }
        return recordCount;
    }

    /** P2#1:按字段类型编码写一个字段值 */
    private void writeField(OutputStream output, Object value, FieldSpec spec, Charset encoding) throws IOException {
        switch (spec.type) {
            case 'N': {
                String s = value == null ? "" : value.toString().trim();
                // 右对齐,左侧填空格;过长截断
                if (s.length() > spec.length) {
                    s = s.substring(s.length() - spec.length);
                }
                StringBuilder padded = new StringBuilder();
                for (int i = s.length(); i < spec.length; i++) padded.append(' ');
                padded.append(s);
                output.write(padded.toString().getBytes(encoding));
                break;
            }
            case 'D': {
                String s = "";
                if (value != null) {
                    if (value instanceof LocalDate ld) {
                        s = ld.format(DATE_FORMATTER);
                    } else {
                        // 尝试按多种常见日期格式解析
                        s = tryParseDate(value.toString());
                    }
                }
                // D 字段固定 8 字节,不足左侧补 '0'(DBF 规范:无效日期为 "00000000")
                byte[] out = new byte[spec.length];
                byte[] sb = s.getBytes(encoding);
                if (sb.length >= spec.length) {
                    System.arraycopy(sb, sb.length - spec.length, out, 0, spec.length);
                } else {
                    for (int i = 0; i < spec.length - sb.length; i++) out[i] = '0';
                    System.arraycopy(sb, 0, out, spec.length - sb.length, sb.length);
                }
                output.write(out);
                break;
            }
            case 'L': {
                // 逻辑型:1 字节,'T' / 'F' / '?'
                char c = '?';
                if (value instanceof Boolean b) {
                    c = b ? 'T' : 'F';
                } else if (value != null) {
                    String vs = value.toString().trim().toLowerCase();
                    if ("true".equals(vs) || "t".equals(vs) || "1".equals(vs) || "y".equals(vs)) c = 'T';
                    else if ("false".equals(vs) || "f".equals(vs) || "0".equals(vs) || "n".equals(vs)) c = 'F';
                }
                byte[] out = new byte[spec.length];
                out[0] = (byte) c;
                output.write(out);
                break;
            }
            case 'C':
            default: {
                String s = value == null ? "" : value.toString();
                byte[] fieldBytes = s.getBytes(encoding);
                if (fieldBytes.length >= spec.length) {
                    output.write(fieldBytes, 0, spec.length);
                } else {
                    output.write(fieldBytes);
                    for (int i = fieldBytes.length; i < spec.length; i++) {
                        output.write(' ');
                    }
                }
                break;
            }
        }
    }

    /** 尝试把任意日期字符串规范化为 YYYYMMDD;失败返回 "00000000" */
    private static String tryParseDate(String raw) {
        if (raw == null) return "00000000";
        String s = raw.trim();
        if (s.isEmpty()) return "00000000";
        // 常见格式
        String[] patterns = {"yyyy-MM-dd", "yyyy/MM/dd", "yyyyMMdd", "yyyy.MM.dd", "yyyy-MM-dd HH:mm:ss"};
        for (String p : patterns) {
            try {
                return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s,
                        DateTimeFormatter.ofPattern(p.length() > 10 ? p.substring(0, 10) : p))
                        .format(DATE_FORMATTER);
            } catch (Exception ignored) {
                // 继续尝试
            }
        }
        return "00000000";
    }

    @Override
    public String getFormat() {
        return "DBF";
    }

    @Override
    public Map<String, String> getDefaultConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("encoding", "GBK");
        config.put("charLength", String.valueOf(DEFAULT_CHAR_LENGTH));
        return config;
    }

    private byte[] intToBcd(int value) {
        int high = value / 100;
        int low = value % 100;
        return new byte[]{(byte) ((high / 10 << 4) | (high % 10)), (byte) ((low / 10 << 4) | (low % 10))};
    }

    private byte[] intToLittleEndian(int value, int bytes) {
        byte[] result = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            result[i] = (byte) (value >> (8 * i));
        }
        return result;
    }

    /** P2#1:字段规范(类型 + 长度 + 小数位) */
    private static class FieldSpec {
        final String name;
        final char type;
        final int length;
        final int decimal;

        FieldSpec(String name, char type, int length, int decimal) {
            this.name = name;
            this.type = type;
            this.length = length;
            this.decimal = decimal;
        }
    }

    /**
     * DBF流式迭代器
     * 流式读取DBF文件，每次返回一批记录
     */
    private static class DbfIterator implements Iterator<List<Map<String, Object>>>, AutoCloseable {
        private final BufferedInputStream input;
        private final Charset encoding;
        private final List<String> fieldNames = new ArrayList<>();
        private final List<Integer> fieldLengths = new ArrayList<>();
        private final List<Character> fieldTypes = new ArrayList<>();
        private int totalRecords = 0;
        private int currentRecord = 0;
        private byte[] buffer;
        private boolean hasNext = true;
        private boolean closed = false;

        DbfIterator(InputStream input, Charset encoding) {
            this.input = new BufferedInputStream(input);
            this.encoding = encoding;
            initialize();
        }

        @Override
        public void close() {
            if (!closed) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
                closed = true;
            }
        }

        /** 初始化：读取文件头和字段描述符 */
        private void initialize() {
            try {
                // 字节0: 版本号 (DBASE III = 0x03, 忽略)
                input.read();
                // 字节1-3: YYMMDD 日期(忽略)
                input.read();
                input.read();
                input.read();
                // 字节4-7: 总记录数 LE int32
                byte[] recordCountBytes = new byte[4];
                input.read(recordCountBytes);
                totalRecords = littleEndianToInt(recordCountBytes);
                // 字节8-9: header 字节数 LE int16
                byte[] headerSizeBytes = new byte[2];
                input.read(headerSizeBytes);
                // 字节10-11: 单条记录字节数 LE int16
                byte[] recordSizeBytes = new byte[2];
                input.read(recordSizeBytes);
                int recordSize = littleEndianToInt(recordSizeBytes);
                // 字节12-31: 保留(20字节)
                for (int i = 0; i < 20; i++) {
                    input.read();
                }

                // 字节32起: 字段描述符, 每个 32 字节, 字段描述符后是 0x0D 终止符
                readFieldDescriptors();

                buffer = new byte[recordSize];
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse DBF header", e);
            }
        }

        /** 读取字段描述符列表 */
        private void readFieldDescriptors() throws IOException {
            while (true) {
                byte[] fieldDesc = new byte[FIELD_DESC_SIZE];
                int read = input.read(fieldDesc);
                if (read < FIELD_DESC_SIZE) {
                    // 文件意外结束
                    break;
                }
                if (fieldDesc[0] == HEADER_TERMINATOR) {
                    // 字段描述符结束
                    break;
                }
                String fieldName = new String(fieldDesc, 0, 10, encoding).trim();
                char fieldType = (char) fieldDesc[11];
                int fieldLen = fieldDesc[16];
                fieldNames.add(fieldName);
                fieldTypes.add(fieldType);
                fieldLengths.add(fieldLen);
            }
        }

        @Override
        public boolean hasNext() {
            return hasNext && currentRecord < totalRecords;
        }

        /** 返回一批记录（流式读取） */
        @Override
        public List<Map<String, Object>> next() {
            List<Map<String, Object>> batch = new ArrayList<>();
            int batchCount = 0;
            while (hasNext && currentRecord < totalRecords && batchCount < DEFAULT_BATCH_SIZE) {
                try {
                    int read = input.read(buffer);
                    if (read < buffer.length) {
                        hasNext = false;
                        break;
                    }
                    if (buffer[0] == EOF_MARKER) {
                        hasNext = false;
                        break;
                    }
                    Map<String, Object> record = parseRecord();
                    batch.add(record);
                    currentRecord++;
                    batchCount++;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse DBF record", e);
                }
            }
            return batch;
        }

        /** 解析单条记录(P2#1:按字段类型还原值) */
        private Map<String, Object> parseRecord() {
            Map<String, Object> record = new HashMap<>();
            int offset = 1;
            for (int i = 0; i < fieldNames.size(); i++) {
                String fieldName = fieldNames.get(i);
                char type = fieldTypes.get(i);
                int len = fieldLengths.get(i);
                String raw = new String(buffer, offset, len, encoding).trim();
                Object value = convertValue(raw, type);
                record.put(fieldName, value);
                offset += len;
            }
            return record;
        }

        /** P2#1:按 DBF 字段类型把字符串还原为 Java 类型 */
        private static Object convertValue(String raw, char type) {
            if (raw == null) return null;
            switch (type) {
                case 'N':
                    if (raw.isEmpty()) return null;
                    try {
                        if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                            return Double.parseDouble(raw);
                        }
                        return Long.parseLong(raw);
                    } catch (NumberFormatException e) {
                        return raw; // 回退到字符串
                    }
                case 'D':
                    if (raw.isEmpty() || "00000000".equals(raw)) return null;
                    try {
                        return LocalDate.parse(raw, DATE_FORMATTER);
                    } catch (Exception e) {
                        return raw;
                    }
                case 'L':
                    if (raw.isEmpty()) return null;
                    char c = Character.toUpperCase(raw.charAt(0));
                    if (c == 'T' || c == 'Y') return Boolean.TRUE;
                    if (c == 'F' || c == 'N') return Boolean.FALSE;
                    return null;
                case 'C':
                default:
                    return raw;
            }
        }
    }

    private static int littleEndianToInt(byte[] bytes) {
        int value = 0;
        for (int i = bytes.length - 1; i >= 0; i--) {
            value = (value << 8) | (bytes[i] & 0xFF);
        }
        return value;
    }
}