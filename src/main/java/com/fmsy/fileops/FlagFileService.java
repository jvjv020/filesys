package com.fmsy.fileops;

import com.fmsy.ftp.FtpClient;
import com.fmsy.util.ResolvedPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标志文件处理服务 — 短关键字 + 模式码体系。
 *
 * <p><b>前置操作</b>（在 TransferSupport.preCheck 中使用）：
 * <ul>
 *   <li>READY:path — 仅检查文件存在</li>
 *   <li>FLAG:path — 仅检查标志存在</li>
 *   <li>FLAG:path;mode — 标志内容 vs 数据文件计算值，按 mode 比对</li>
 *   <li>FLAG:path;expect;mode — 显式期望值 vs 数据文件计算值</li>
 * </ul>
 *
 * <p><b>后置操作</b>（通过 process() 执行）：
 * <ul>
 *   <li>SUB:path;content — 子标志文件</li>
 *   <li>FB:path;content — 反馈文件</li>
 *   <li>TOTAL:path;content — 总标志文件</li>
 *   <li>DEL:path — 删除匹配文件</li>
 *   <li>REN:from;to — 重命名（* 替换为源文件名）</li>
 *   <li>MSG:target;body — 发送消息</li>
 * </ul>
 *
 * <p><b>路径继承</b>：不以 "/" 开头的 pattern 自动加 "{dir}/" 前缀。
 *
 * <p><b>模式码</b>（content 和 FLAG mode 共用）：
 * <ul>
 *   <li>L — 行数  S — 字节数  M — MD5  C — 处理记录数</li>
 *   <li>N — 时间戳  D — 日期  T — 时间</li>
 *   <li>F — 文件名(含扩展)  X — 文件名(去扩展)  E — 扩展名  P — 完整路径</li>
 * </ul>
 *
 * <p>FLAG mode 语法：{@code [#|@]L|M|S[=|>|<|>=|<=|!=] | ?}
 * <ul>
 *   <li># — 从数据文件计算  @ — 从标志文件读取（默认）</li>
 *   <li>? — 仅检查存在</li>
 * </ul>
 */
@Slf4j
@Service
public class FlagFileService {

    /** 操作类型到执行器的映射 */
    private final Map<String, FlagOperation> operations = new ConcurrentHashMap<>();

    private final ContentEngine contentEngine = new ContentEngine();

    public FlagFileService() {
        this(new MessageSender());
    }

    public FlagFileService(MessageSender messageSender) {
        operations.put("SUB", new GenerateOp("sub flag", contentEngine));
        operations.put("FB", new GenerateOp("feedback", contentEngine));
        operations.put("TOTAL", new GenerateOp("total flag", contentEngine));
        operations.put("DEL", new DeleteOp());
        operations.put("REN", new RenameOp());
        operations.put("MSG", new SendMessageOp(messageSender));
    }

    // ==================== 前置检查方法 ====================

    /**
     * 前置检查入口 — 解析 READY/FLAG 操作串。
     *
     * @param opsStr  前置操作字符串，多个以 "," 分隔
     * @param fileInfo 数据文件的 ResolvedPath，用于 {stem}/{dir} 等路径变量和 L/S/M 计算
     * @return true=通过，false=跳过
     */
    public boolean preCheck(FtpClient client, String opsStr, ResolvedPath fileInfo) {
        if (opsStr == null || opsStr.isEmpty()) return true;

        for (String op : opsStr.split(",")) {
            String[] parts = op.split(";");
            String keyword = parts.length > 0 ? parts[0].trim() : "";

            switch (keyword) {
                case "READY" -> {
                    String pattern = resolvePath(parts, 1, fileInfo);
                    if (!checkReady(client, pattern)) return false;
                }
                case "FLAG" -> {
                    String pattern = resolvePath(parts, 1, fileInfo);
                    if (parts.length == 2) {
                        // FLAG:path → 仅检查存在
                        if (!checkReady(client, pattern)) return false;
                    } else if (parts.length == 3) {
                        // FLAG:path;mode
                        String mode = parts[2].trim();
                        if (!checkFlagByMode(client, pattern, mode, fileInfo)) return false;
                    } else if (parts.length >= 4) {
                        // FLAG:path;expect;mode
                        String expectVal = parts[2].trim();
                        String mode = parts[3].trim();
                        if (!checkFlagByMode(client, pattern, mode, expectVal, fileInfo)) return false;
                    }
                }
                default ->
                    log.warn("Unknown pre-operation keyword: {}", keyword);
            }
        }
        return true;
    }

    /** 仅检查文件存在 */
    public boolean checkReady(FtpClient client, String filePattern) {
        boolean exists = client.exists(filePattern);
        if (!exists) {
            log.warn("Ready/flag file not found: {}", filePattern);
        }
        return exists;
    }

    /**
     * FLAG 自动模式：标志文件内容作为期望值，按 mode 计算数据文件实际值，比对。
     */
    private boolean checkFlagByMode(FtpClient client, String flagPattern, String mode,
                                     ResolvedPath dataFile) {
        if ("?".equals(mode)) {
            return checkReady(client, flagPattern);
        }
        // 读标志文件内容作为期望值
        String expect = readFlagContent(client, flagPattern);
        if (expect == null) return false;
        return checkFlagCompare(client, flagPattern, expect, mode, dataFile);
    }

    /**
     * FLAG 显式模式：expect 字面作为期望值，按 mode 计算数据文件实际值，比对。
     */
    private boolean checkFlagByMode(FtpClient client, String flagPattern, String mode,
                                     String expect, ResolvedPath dataFile) {
        return checkFlagCompare(client, flagPattern, expect, mode, dataFile);
    }

    /**
     * FLAG 比对核心：根据 mode 解析取值码和比较符，计算实际值，比对。
     */
    private boolean checkFlagCompare(FtpClient client, String flagPattern,
                                      String expect, String mode, ResolvedPath dataFile) {
        // 解析 mode:  [@|#]L|M|S[=|>|<|>=|<=|!=]
        String metricStr;
        String comparison = "=";
        String src = "#"; // 默认从数据文件计算

        String m = mode.trim();
        if (m.startsWith("@") || m.startsWith("#")) {
            src = m.substring(0, 1);
            m = m.substring(1);
        }

        // 提取比较符
        int opStart = -1;
        for (int i = 0; i < m.length(); i++) {
            char c = m.charAt(i);
            if (c == '=' || c == '>' || c == '<' || c == '!') {
                opStart = i;
                break;
            }
        }
        if (opStart >= 0) {
            metricStr = m.substring(0, opStart);
            comparison = m.substring(opStart);
        } else {
            metricStr = m;
        }

        // 计算实际值
        String actual = computeMetric(client, metricStr, dataFile);
        if (actual == null) return false;

        // 执行比对
        boolean pass = compareValues(expect, actual, comparison);
        if (!pass) {
            log.warn("FLAG check failed: {} expected '{}' {} actual '{}' (metric={})",
                    flagPattern, expect, comparison, actual, metricStr);
        }
        return pass;
    }

    /** 读取标志文件内容（首行） */
    private String readFlagContent(FtpClient client, String path) {
        try (InputStream is = client.getInputStream(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.readLine();
        } catch (Exception e) {
            log.error("Failed to read flag file: {}", path, e);
            return null;
        }
    }

    /**
     * 按 metric 码计算数据文件的实际值。
     * L=行数, S=字节数, M=MD5, 其他→null
     */
    private String computeMetric(FtpClient client, String metric, ResolvedPath dataFile) {
        if (dataFile == null || dataFile.fullPath() == null) return null;
        String path = dataFile.fullPath();
        return switch (metric.toUpperCase()) {
            case "L" -> String.valueOf(client.countFileLines(path));
            case "S" -> String.valueOf(client.getFileSize(path));
            case "M" -> client.computeMd5(path);
            default -> {
                log.warn("Unknown FLAG metric: {}", metric);
                yield null;
            }
        };
    }

    /** 两个字符串值的数值比较 */
    private boolean compareValues(String expect, String actual, String op) {
        if (expect == null || actual == null) return false;
        try {
            long e = Long.parseLong(expect.trim());
            long a = Long.parseLong(actual.trim());
            return switch (op) {
                case "=", "==" -> e == a;
                case ">" -> a > e;
                case "<" -> a < e;
                case ">=" -> a >= e;
                case "<=" -> a <= e;
                case "!=" -> a != e;
                default -> expect.equals(actual);
            };
        } catch (NumberFormatException e) {
            // 非数值 → 字符串比较
            return switch (op) {
                case "=", "==" -> expect.trim().equals(actual.trim());
                case "!=" -> !expect.trim().equals(actual.trim());
                default -> expect.trim().equals(actual.trim());
            };
        }
    }

    // ==================== 后置处理方法 ====================

    /**
     * 处理后置操作字符串（无文件衍生变量，向后兼容）
     */
    public void process(FtpClient client, String operationsStr) {
        process(client, operationsStr, null, null);
    }

    /**
     * 处理后置操作字符串，支持 ResolvedPath 文件衍生变量。
     *
     * @param operationsStr 后置操作字符串
     * @param fileInfo 数据文件的 ResolvedPath，用于路径继承和内容变量
     * @param extraValues 额外运行时值（如 C=记录数），可为 null
     */
    public void process(FtpClient client, String operationsStr, ResolvedPath fileInfo,
                        Map<String, String> extraValues) {
        if (operationsStr == null || operationsStr.isEmpty()) return;

        for (String op : operationsStr.split(",")) {
            String[] parts = op.split(";");
            if (parts.length == 0) continue;
            String keyword = parts[0].trim();
            FlagOperation operation = operations.get(keyword);
            if (operation == null) {
                log.warn("Unknown post-operation keyword: {}", keyword);
                continue;
            }
            try {
                if (operation instanceof GenerateOp gen) {
                    gen.execute(client, parts, fileInfo, extraValues);
                } else {
                    operation.execute(client, parts);
                }
            } catch (Exception e) {
                log.error("Failed to process operation: {}", keyword, e);
            }
        }
    }

    // ==================== 路径解析工具 ====================

    /**
     * 从操作 parts 中取指定索引的路径段，做路径继承处理。
     */
    String resolvePath(String[] parts, int index, ResolvedPath fileInfo) {
        String raw = parts.length > index ? parts[index].trim() : "";
        return resolvePath(raw, fileInfo);
    }

    /**
     * 路径继承：不以 "/" 开头 → 自动加 "{dir}/" 前缀，并规范化 ".."。
     */
    String resolvePath(String raw, ResolvedPath fileInfo) {
        if (raw == null || raw.isEmpty()) return raw;
        if (raw.startsWith("/")) return raw; // 绝对路径，不变
        if (fileInfo == null || fileInfo.dir() == null || fileInfo.dir().isEmpty()) return raw;
        // 先对 raw 做文件衍生变量替换
        String resolved = contentEngine.expandPathVariables(raw, fileInfo);
        String full = fileInfo.dir() + "/" + resolved;
        return normalizePath(full);
    }

    /**
     * 规范化路径中的 ".." 段。
     * 例如: /data/export/BR001/../all.flg → /data/export/all.flg
     */
    static String normalizePath(String path) {
        if (path == null || !path.contains("..")) return path;
        String[] segments = path.split("/");
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String seg : segments) {
            if ("..".equals(seg)) {
                if (!result.isEmpty()) result.remove(result.size() - 1);
            } else if (!seg.isEmpty()) {
                result.add(seg);
            }
        }
        return "/" + String.join("/", result);
    }

    // ==================== 公共工具方法 ====================

    /**
     * 从操作字符串中过滤出指定操作类型的子串。
     */
    public static String filterOpsByType(String operationsStr, String opType) {
        if (operationsStr == null || operationsStr.isEmpty()) return null;
        if (opType == null) return operationsStr;
        StringBuilder sb = new StringBuilder();
        for (String op : operationsStr.split(",")) {
            String[] parts = op.split(";");
            if (parts.length > 0 && opType.equalsIgnoreCase(parts[0].trim())) {
                if (sb.length() > 0) sb.append(",");
                sb.append(op);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 从文件路径中提取所在目录的目录名（最后一段路径）。
     */
    public static String extractDirname(String filePath) {
        if (filePath == null) return null;
        int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash < 0) return filePath;
        String dir = filePath.substring(0, lastSlash);
        int secondLastSlash = dir.lastIndexOf('/');
        return secondLastSlash >= 0 ? dir.substring(secondLastSlash + 1) : dir;
    }

    // ==================== 模式码引擎 ====================

    /**
     * 模式码引擎 — 将 content 和 FLAG mode 中的单字母码替换为运行时值。
     *
     * <p>单字母模式码（L/S/M/C/N/D/T/F/X/E/P）在独立出现（不与相邻大写字母连写）时被替换。
     * 多字母大写词如 "SUCCESS"、"OK"、"ERROR" 保持原样。
     */
    static class ContentEngine {

        private static final DateTimeFormatter DT_FMT =
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        private static final DateTimeFormatter D_FMT =
                DateTimeFormatter.ofPattern("yyyyMMdd");
        private static final DateTimeFormatter T_FMT =
                DateTimeFormatter.ofPattern("HHmmss");

        /**
         * 扩展内容模板：将模式码替换为运行时值。
         *
         * @param template 内容模板，如 "L S M" 或 "C rows at N"
         * @param fileInfo 数据文件信息，可为 null
         * @param lines    行数，null 时从 fileInfo + client 获取（client 不为 null 时）
         * @param size     字节数
         * @param md5      MD5
         * @param count    处理记录数
         * @param client   FTP 客户端，用于延迟计算 L/S/M
         * @return 替换后的字符串
         */
        String expand(String template, ResolvedPath fileInfo,
                      String lines, String size, String md5,
                      String count, FtpClient client) {
            if (template == null) return null;

            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < template.length()) {
                char c = template.charAt(i);

                // 转义字符
                if (c == '\\' && i + 1 < template.length()) {
                    char next = template.charAt(i + 1);
                    if (next == 't') { sb.append('\t'); i += 2; continue; }
                    if (next == 'n') { sb.append('\n'); i += 2; continue; }
                }

                // 单大写字母模式码检测：前后不能有大写字母相邻
                if (c >= 'A' && c <= 'Z') {
                    boolean prevIsUpper = i > 0 && template.charAt(i - 1) >= 'A' && template.charAt(i - 1) <= 'Z';
                    boolean nextIsUpper = i + 1 < template.length() && template.charAt(i + 1) >= 'A' && template.charAt(i + 1) <= 'Z';
                    if (!prevIsUpper && !nextIsUpper) {
                        String val = resolveCode(String.valueOf(c), fileInfo,
                                lines, size, md5, count, client);
                        if (val != null) {
                            sb.append(val);
                            i++;
                            continue;
                        }
                    }
                }

                sb.append(c);
                i++;
            }
            return sb.toString();
        }

        /**
         * 仅替换路径变量（{stem}/{name}/{ext}/{dir}/{dn}/{up}），不做模式码展开。
         * 用于操作路径中的变量替换。
         */
        String expandPathVariables(String template, ResolvedPath fileInfo) {
            if (template == null || fileInfo == null) return template;
            return template
                    .replace("{stem}", nullToEmpty(fileInfo.stem()))
                    .replace("{name}", nullToEmpty(fileInfo.name()))
                    .replace("{ext}", nullToEmpty(fileInfo.ext()))
                    .replace("{dir}", nullToEmpty(fileInfo.dir()))
                    .replace("{dn}", nullToEmpty(fileInfo.dn()))
                    .replace("{up}", nullToEmpty(fileInfo.up()));
        }

        private String nullToEmpty(String s) {
            return s == null ? "" : s;
        }

        private String resolveCode(String code, ResolvedPath fileInfo,
                                    String lines, String size, String md5,
                                    String count, FtpClient client) {
            // 先检查直接传入的值
            return switch (code) {
                case "L" -> lines != null ? lines : (client != null && fileInfo != null
                        ? String.valueOf(client.countFileLines(fileInfo.fullPath())) : null);
                case "S" -> size != null ? size : (client != null && fileInfo != null
                        ? String.valueOf(client.getFileSize(fileInfo.fullPath())) : null);
                case "M" -> md5 != null ? md5 : (client != null && fileInfo != null
                        ? client.computeMd5(fileInfo.fullPath()) : null);
                case "C" -> count;
                case "N" -> LocalDateTime.now().format(DT_FMT);
                case "D" -> LocalDateTime.now().format(D_FMT);
                case "T" -> LocalDateTime.now().format(T_FMT);
                case "F" -> fileInfo != null ? fileInfo.name() : null;
                case "X" -> fileInfo != null ? fileInfo.stem() : null;
                case "E" -> fileInfo != null ? fileInfo.ext() : null;
                case "P" -> fileInfo != null ? fileInfo.fullPath() : null;
                default -> null; // 非模式码，原样保留
            };
        }
    }

    // ==================== 内部类：操作实现 ====================

    interface FlagOperation {
        void execute(FtpClient client, String[] parts) throws Exception;
    }

    /** 生成标志/反馈文件操作 */
    @Slf4j
    static class GenerateOp implements FlagOperation {
        private final String flagType;
        private final ContentEngine contentEngine;

        GenerateOp(String flagType, ContentEngine contentEngine) {
            this.flagType = flagType;
            this.contentEngine = contentEngine;
        }

        @Override
        public void execute(FtpClient client, String[] parts) throws Exception {
            execute(client, parts, null, null);
        }

        void execute(FtpClient client, String[] parts, ResolvedPath fileInfo,
                      Map<String, String> extraValues) throws Exception {
            String filePattern = parts.length > 1 ? parts[1].trim() : "";
            String content = parts.length > 2 ? parts[2].trim() : "";

            // 路径继承 + .. 规范化
            if (fileInfo != null && !filePattern.startsWith("/")) {
                filePattern = contentEngine.expandPathVariables(filePattern, fileInfo);
                filePattern = normalizePath(fileInfo.dir() + "/" + filePattern);
            }

            // 内容模式码展开
            String lines = extraValues != null ? extraValues.get("L") : null;
            String size = extraValues != null ? extraValues.get("S") : null;
            String md5 = extraValues != null ? extraValues.get("M") : null;
            String count = extraValues != null ? extraValues.get("C") : null;
            String resolvedContent = contentEngine.expand(content, fileInfo, lines, size, md5, count, client);

            try (OutputStream os = client.getOutputStream(filePattern)) {
                byte[] bytes = resolvedContent.getBytes(StandardCharsets.UTF_8);
                os.write(bytes);
                client.completePendingCommand();
            }
            log.info("Generated {} flag file: {} ({} bytes)", flagType, filePattern,
                    resolvedContent != null ? resolvedContent.length() : 0);
        }
    }

    /** 删除文件操作 */
    @Slf4j
    static class DeleteOp implements FlagOperation {
        @Override
        public void execute(FtpClient client, String[] parts) throws Exception {
            String pattern = parts.length > 1 ? parts[1].trim() : "";
            if (pattern.isEmpty()) return;
            String[] files = client.listFiles(pattern);
            for (String file : files) {
                if (client.deleteFile(file)) {
                    log.info("Deleted file: {}", file);
                } else {
                    log.warn("Failed to delete file: {}", file);
                }
            }
        }
    }

    /** 重命名操作 */
    @Slf4j
    static class RenameOp implements FlagOperation {
        @Override
        public void execute(FtpClient client, String[] parts) throws Exception {
            String from = parts.length > 1 ? parts[1].trim() : "";
            String to = parts.length > 2 ? parts[2].trim() : "";
            if (from.isEmpty() || to.isEmpty()) return;
            String[] files = client.listFiles(from);
            for (String file : files) {
                String targetPath = to.replace("*", file.substring(file.lastIndexOf('/') + 1));
                if (client.rename(file, targetPath)) {
                    log.info("Renamed file: {} -> {}", file, targetPath);
                } else {
                    log.warn("Failed to rename file: {} -> {}", file, targetPath);
                }
            }
        }
    }

    /** 发送消息操作 */
    @Slf4j
    static class SendMessageOp implements FlagOperation {
        private final MessageSender messageSender;

        SendMessageOp(MessageSender messageSender) {
            this.messageSender = messageSender;
        }

        @Override
        public void execute(FtpClient client, String[] parts) throws Exception {
            String target = parts.length > 1 ? parts[1].trim() : "";
            String message = parts.length > 2 ? parts[2].trim() : "";
            messageSender.send(target, message);
        }
    }

    // ==================== 检查结果枚举 ====================

    public enum CheckResult {
        PASS, SKIP, ERROR
    }
}
