package com.fmsy.fileops;

import com.fmsy.exception.FlagCheckException;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.MessageConfig;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ResolvedPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标志文件处理服务 — 短关键字 + 内容编号 + 文件名短码体系。
 *
 * <p><b>前置操作</b>（在 TransferSupport.preCheck 中使用）：
 * <ul>
 *   <li>L:path — 仅检查文件存在（等价于旧 READY）</li>
 *   <li>L:path;nn — 检查标志存在，按内容编号 nn 比对内容</li>
 * </ul>
 *
 * <p><b>后置操作</b>（通过 process() 执行）：
 * <ul>
 *   <li>F:path;nn — 反馈文件</li>
 *   <li>U:path;nn — 子标志文件</li>
 *   <li>T:path;nn — 总标志文件</li>
 *   <li>D:path — 删除匹配文件</li>
 *   <li>R:from;to — 重命名（* 替换为源文件名）</li>
 *   <li>M — 发送消息（从 config 取代号查消息表）</li>
 * </ul>
 *
 * <p><b>文件名短码</b>（在路径中出现时展开）：
 * <ul>
 *   <li>S → {stem}  N → {name}  E → {ext}</li>
 *   <li>D → {dir}  W → {dn}  H → {up}  P → {path}</li>
 * </ul>
 * 示例：{@code S.ok} → {@code {stem}.ok}，{@code D/S.flg} → {@code {dir}/{stem}.flg}
 *
 * <p><b>路径继承</b>：不以 "/" 开头的 pattern 自动加 "{dir}/" 前缀。
 *
 * <p><b>内容编号</b>：见 {@link ContentCode}，生成和检查共用同一套编号（对称性）。
 * 编号 {@code 00}（空内容）或省略时：
 * <ul>
 *   <li>L 场景：只检查文件存在，不比对内容</li>
 *   <li>F/U/T 场景：生成空文件</li>
 * </ul>
 */
@Slf4j
@Service
public class FlagFileService {

    /** 操作类型到执行器的映射 */
    private final Map<String, FlagOperation> operations = new ConcurrentHashMap<>();

    private final ContentEngine contentEngine = new ContentEngine();
    private final MessageConfigService messageConfigService;

    /**
     * 文件名短码 → 完整变量名 映射表。
     * 单字母短码仅在独立出现（不与相邻字母连写）时替换。
     */
    private static final Map<Character, String> FILE_SHORT_CODES = new HashMap<>();

    static {
        FILE_SHORT_CODES.put('S', "{stem}");
        FILE_SHORT_CODES.put('N', "{name}");
        FILE_SHORT_CODES.put('E', "{ext}");
        FILE_SHORT_CODES.put('D', "{dir}");
        FILE_SHORT_CODES.put('W', "{dn}");
        FILE_SHORT_CODES.put('H', "{up}");
        FILE_SHORT_CODES.put('P', "{path}");
    }

    public FlagFileService(MessageConfigService messageConfigService) {
        this(messageConfigService, new MessageSender());
    }

    public FlagFileService(MessageConfigService messageConfigService, MessageSender messageSender) {
        this.messageConfigService = messageConfigService;
        operations.put("U", new GenerateOp("sub flag", contentEngine));
        operations.put("F", new GenerateOp("feedback", contentEngine));
        operations.put("T", new GenerateOp("total flag", contentEngine));
        operations.put("D", new DeleteOp());
        operations.put("R", new RenameOp());
        operations.put("M", new SendMessageOp(messageSender, messageConfigService));
    }

    // ==================== 文件名短码展开 ====================

    /**
     * 展开路径中的文件名短码为完整变量名。
     *
     * <p>单字母短码仅在两侧都不是字母时替换，避免误伤普通单词。
     * 例如 "S.ok" → "{stem}.ok"，"DATA" 中的 S 不替换。
     *
     * @param path 含短码的路径，如 "S.ok" 或 "D/S.flg"
     * @return 展开后的路径，如 "{stem}.ok" 或 "{dir}/{stem}.flg"
     */
    public static String expandFileNameShortCodes(String path) {
        if (path == null || path.isEmpty()) return path;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            // 检查是否已知短码且独立出现
            if (FILE_SHORT_CODES.containsKey(c)) {
                boolean prevIsLetter = i > 0 && Character.isLetter(path.charAt(i - 1));
                boolean nextIsLetter = i + 1 < path.length() && Character.isLetter(path.charAt(i + 1));
                if (!prevIsLetter && !nextIsLetter) {
                    sb.append(FILE_SHORT_CODES.get(c));
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ==================== 前置检查方法 ====================

    /**
     * 前置检查入口 — 解析 L（Flag Check）操作串。
     *
     * <p>格式：L:path[;nn]，多个以 "," 分隔。
     * 省略 nn 或 nn=00 时只检查文件存在；nn 非空时按内容编号比对。
     *
     * @param opsStr   前置操作字符串，如 "L:S.ok;03" 或 "L:*.flg"
     * @param fileInfo 数据文件的 ResolvedPath，用于路径继承和变量替换
     * @return true=通过，false=跳过
     */
    public boolean preCheck(FtpClient client, String opsStr, ResolvedPath fileInfo) {
        if (opsStr == null || opsStr.isEmpty()) return true;

        for (String op : opsStr.split(",")) {
            op = op.trim();
            if (op.isEmpty()) continue;

            // 格式: L:path[;nn]
            int colonIdx = op.indexOf(':');
            if (colonIdx <= 0) {
                log.warn("Invalid pre-operation format: {}", op);
                continue;
            }
            String keyword = op.substring(0, colonIdx).trim();
            String rest = op.substring(colonIdx + 1).trim();

            if (!"L".equals(keyword)) {
                log.warn("Unknown pre-operation keyword: {}", keyword);
                continue;
            }

            // 解析 rest = path[;nn]
            String[] parts = rest.split(";");
            String path = parts[0].trim();
            String code = parts.length > 1 ? parts[1].trim() : "";
            String pattern = resolvePath(path, fileInfo);

            if (ContentCode.isEmpty(code)) {
                // L:path 或 L:path;00 — 仅检查存在
                if (!checkExists(client, pattern)) return false;
            } else {
                // L:path;nn — 内容比对
                if (!checkFlagByCode(client, pattern, code, fileInfo)) return false;
            }
        }
        return true;
    }

    /** 仅检查文件存在 */
    public boolean checkExists(FtpClient client, String filePattern) {
        boolean exists = client.exists(filePattern);
        if (!exists) {
            log.warn("File not found: {}", filePattern);
        }
        return exists;
    }

    /**
     * FLAG 按内容编号比对：读取标志文件内容，按编号模板计算数据文件实际值，比对。
     */
    private boolean checkFlagByCode(FtpClient client, String flagPattern, String codeStr,
                                    ResolvedPath dataFile) {
        // 先检查标志文件是否存在
        if (!checkExists(client, flagPattern)) {
            return false;
        }
        // 读内容作为期望值
        String expect = readFlagContent(client, flagPattern);
        if (expect == null) {
            throw new FlagCheckException("FLAG file is empty: " + flagPattern);
        }
        // 按编号模板计算数据文件实际值
        String actual = computeFromTemplate(client, codeStr, dataFile);
        if (actual == null) {
            throw new FlagCheckException("FLAG check failed: unable to compute content code '"
                    + codeStr + "' for " + dataFile);
        }
        // 比对
        boolean pass = expect.trim().equals(actual.trim());
        if (!pass) {
            String msg = String.format("FLAG check failed: %s expected '%s' actual '%s' (code=%s)",
                    flagPattern, expect, actual, codeStr);
            log.warn(msg);
            throw new FlagCheckException(msg);
        }
        return true;
    }

    /**
     * 按内容编号模板从数据文件计算值。
     * 读取编号对应的模板，展开模式码，返回展开后的字符串。
     */
    private String computeFromTemplate(FtpClient client, String codeStr, ResolvedPath dataFile) {
        ContentCode code = ContentCode.fromCode(codeStr);
        if (code == null) {
            log.warn("Unknown content code: {}", codeStr);
            return null;
        }
        // 使用 ContentEngine 展开模板中的模式码
        return contentEngine.expand(code.getTemplate(), dataFile, null, null, null, null, client);
    }

    /** 读取标志文件内容（首行，清理 \r 残留） */
    private String readFlagContent(FtpClient client, String path) {
        try (InputStream is = client.getInputStream(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line = reader.readLine();
            return line != null ? line.replace("\r", "") : null;
        } catch (Exception e) {
            log.error("Failed to read flag file: {}", path, e);
            return null;
        }
    }

    // ==================== 后置处理方法 ====================

    /**
     * 处理后置操作字符串（无文件衍生变量，向后兼容）
     */
    public void process(FtpClient client, String operationsStr) {
        process(client, operationsStr, null, null, null, null);
    }

    /**
     * 处理后置操作字符串，支持 ResolvedPath 文件衍生变量。
     */
    public void process(FtpClient client, String operationsStr, ResolvedPath fileInfo,
                        Map<String, String> extraValues) {
        process(client, operationsStr, fileInfo, extraValues, null, null);
    }

    /**
     * 处理后置操作字符串，完整参数版。
     *
     * @param operationsStr 后置操作字符串
     * @param fileInfo       数据文件的 ResolvedPath，用于路径继承和内容变量
     * @param extraValues    额外运行时值（如 C=记录数，L=行数），可为 null
     * @param categoryCode   类别代号（MSG 消息查询用），可为 null
     * @param controlCode    控制代号（MSG 消息查询用），可为 null
     */
    public void process(FtpClient client, String operationsStr, ResolvedPath fileInfo,
                        Map<String, String> extraValues, String categoryCode, String controlCode) {
        if (operationsStr == null || operationsStr.isEmpty()) return;

        for (String op : operationsStr.split(",")) {
            op = op.trim();
            if (op.isEmpty()) continue;

            // 格式: KEYWORD:rest 或 KEYWORD（MSG 无 rest）
            int colonIdx = op.indexOf(':');
            String keyword, rest;
            if (colonIdx > 0) {
                keyword = op.substring(0, colonIdx).trim();
                rest = op.substring(colonIdx + 1).trim();
            } else {
                keyword = op.trim();
                rest = "";
            }

            FlagOperation operation = operations.get(keyword);
            if (operation == null) {
                log.warn("Unknown post-operation keyword: {}", keyword);
                continue;
            }
            try {
                // 将 rest 按 ; 拆分为 parts 数组，keyword 作为 parts[0]
                String[] parts;
                if (rest.isEmpty()) {
                    parts = new String[]{keyword};
                } else {
                    String[] restParts = rest.split(";", -1);
                    parts = new String[restParts.length + 1];
                    parts[0] = keyword;
                    System.arraycopy(restParts, 0, parts, 1, restParts.length);
                }
                operation.execute(client, parts, fileInfo, extraValues, categoryCode, controlCode);
            } catch (Exception e) {
                log.error("Failed to process operation: {}", keyword, e);
            }
        }
    }

    // ==================== 路径解析工具 ====================

    /**
     * 从操作 parts 中取指定索引的路径段，做短码展开 + 路径继承。
     */
    String resolvePath(String[] parts, int index, ResolvedPath fileInfo) {
        String raw = parts.length > index ? parts[index].trim() : "";
        return resolvePath(raw, fileInfo);
    }

    /**
     * 路径处理：短码展开 → 路径继承 → .. 规范化。
     * 不以 "/" 开头 → 自动加 "{dir}/" 前缀。
     */
    String resolvePath(String raw, ResolvedPath fileInfo) {
        if (raw == null || raw.isEmpty()) return raw;

        // 1. 展开文件名短码
        String expanded = expandFileNameShortCodes(raw);

        // 2. 绝对路径不变
        if (expanded.startsWith("/")) {
            // 仍需展开文件衍生变量（如 {stem} 已在短码展开后出现）
            return normalizePath(contentEngine.expandPathVariables(expanded, fileInfo));
        }
        if (fileInfo == null || fileInfo.dir() == null || fileInfo.dir().isEmpty()) return expanded;

        // 3. 路径继承：先展开文件衍生变量，再 resolveRelative
        String resolved = contentEngine.expandPathVariables(expanded, fileInfo);
        return normalizePath(fileInfo.resolveRelative(resolved));
    }

    /**
     * 规范化路径中的 ".." 段。
     */
    public static String normalizePath(String path) {
        if (path == null || !path.contains("..")) return path;
        String[] segments = path.split("/");
        ArrayList<String> result = new ArrayList<>();
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
     * <p>操作类型是 {@code KEYWORD:rest} 中的 KEYWORD 部分。
     */
    public static String filterOpsByType(String operationsStr, String opType) {
        if (operationsStr == null || operationsStr.isEmpty()) return null;
        if (opType == null) return operationsStr;
        StringBuilder sb = new StringBuilder();
        for (String op : operationsStr.split(",")) {
            op = op.trim();
            if (op.isEmpty()) continue;
            // 提取 : 前的关键字
            int colonIdx = op.indexOf(':');
            String keyword = colonIdx > 0 ? op.substring(0, colonIdx).trim() : op.trim();
            if (keyword.isEmpty()) continue;
            if (opType.equalsIgnoreCase(keyword)) {
                if (sb.length() > 0) sb.append(",");
                sb.append(op);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 从前置操作字符串中提取第一个 L 操作的路径模式（不含 content code 后缀）。
     */
    public static String extractFlagPathPattern(String preOps) {
        if (preOps == null || preOps.isEmpty()) return null;
        for (String op : preOps.split(",")) {
            op = op.trim();
            if (!op.startsWith("L:")) continue;
            String pathPart = op.substring(2).trim();
            if (pathPart.isEmpty()) continue;
            int semicolon = pathPart.indexOf(';');
            return semicolon > 0 ? pathPart.substring(0, semicolon).trim() : pathPart;
        }
        return null;
    }

    // ==================== 模式码引擎 ====================

    /**
     * 模式码引擎 — 将内容模板中的单字母模式码替换为运行时值。
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
         * 替换路径变量（{stem}/{name}/{ext}/{dir}/{dn}/{up}），不做模式码展开。
         */
        String expandPathVariables(String template, ResolvedPath fileInfo) {
            return TransferSupport.expandPathVariables(template, fileInfo);
        }

        private String resolveCode(String code, ResolvedPath fileInfo,
                                    String lines, String size, String md5,
                                    String count, FtpClient client) {
            return switch (code) {
                case "L" -> lines != null ? lines : (client != null && fileInfo != null
                        ? safeCountFileLines(client, fileInfo.fullPath()) : null);
                case "S" -> size != null ? size : (client != null && fileInfo != null
                        ? safeGetFileSize(client, fileInfo.fullPath()) : null);
                case "M" -> md5 != null ? md5 : (client != null && fileInfo != null
                        ? safeComputeMd5(client, fileInfo.fullPath()) : null);
                case "C" -> count;
                case "N" -> LocalDateTime.now().format(DT_FMT);
                case "D" -> LocalDateTime.now().format(D_FMT);
                case "T" -> LocalDateTime.now().format(T_FMT);
                case "F" -> fileInfo != null ? fileInfo.name() : null;
                case "X" -> fileInfo != null ? fileInfo.stem() : null;
                case "E" -> fileInfo != null ? fileInfo.ext() : null;
                case "P" -> fileInfo != null ? fileInfo.fullPath() : null;
                default -> null;
            };
        }

        private String safeCountFileLines(FtpClient client, String path) {
            try { return String.valueOf(client.countFileLines(path)); }
            catch (IOException e) { throw new RuntimeException(e); }
        }

        private String safeGetFileSize(FtpClient client, String path) {
            try { return String.valueOf(client.getFileSize(path)); }
            catch (IOException e) { throw new RuntimeException(e); }
        }

        private String safeComputeMd5(FtpClient client, String path) {
            return client.computeMd5(path);
        }
    }

    // ==================== 内部类：操作实现 ====================

    interface FlagOperation {
        /** 基本执行方法，兼容旧调用 */
        default void execute(FtpClient client, String[] parts) throws Exception {
            execute(client, parts, null, null, null, null);
        }

        /** 完整执行方法，含文件衍生信息和消息代号 */
        default void execute(FtpClient client, String[] parts,
                             ResolvedPath fileInfo, Map<String, String> extraValues,
                             String categoryCode, String controlCode) throws Exception {
            execute(client, parts);
        }
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
            execute(client, parts, null, null, null, null);
        }

        @Override
        public void execute(FtpClient client, String[] parts, ResolvedPath fileInfo,
                            Map<String, String> extraValues,
                            String categoryCode, String controlCode) throws Exception {
            String filePattern = parts.length > 1 ? parts[1].trim() : "";
            String contentCode = parts.length > 2 ? parts[2].trim() : "";

            // 展开文件名短码
            filePattern = expandFileNameShortCodes(filePattern);

            // 路径继承 + .. 规范化
            if (fileInfo != null && !filePattern.startsWith("/")) {
                filePattern = normalizePath(fileInfo.resolveRelative(
                        contentEngine.expandPathVariables(filePattern, fileInfo)));
            } else if (fileInfo != null) {
                // 绝对路径也要展开文件衍生变量
                filePattern = contentEngine.expandPathVariables(filePattern, fileInfo);
            }

            // 按内容编号获取模板并展开
            String resolvedContent = resolveContent(contentCode, fileInfo, extraValues, client);

            try (OutputStream os = client.getOutputStream(filePattern)) {
                byte[] bytes = (resolvedContent != null ? resolvedContent : "")
                        .getBytes(StandardCharsets.UTF_8);
                os.write(bytes);
                client.completePendingCommand();
            }
            log.info("Generated {} file: {} ({} bytes)", flagType, filePattern,
                    resolvedContent != null ? resolvedContent.length() : 0);
        }

        /**
         * 按内容编号解析实际内容。
         * 编号 00 或空 → 空字符串。
         */
        private String resolveContent(String codeStr, ResolvedPath fileInfo,
                                      Map<String, String> extraValues, FtpClient client) {
            if (ContentCode.isEmpty(codeStr)) return "";

            ContentCode code = ContentCode.fromCode(codeStr);
            if (code == null) {
                log.warn("Unknown content code: {}, using empty", codeStr);
                return "";
            }

            String lines = extraValues != null ? extraValues.get("L") : null;
            String size = extraValues != null ? extraValues.get("S") : null;
            String md5 = extraValues != null ? extraValues.get("M") : null;
            String count = extraValues != null ? extraValues.get("C") : null;

            return contentEngine.expand(code.getTemplate(), fileInfo, lines, size, md5, count, client);
        }
    }

    /** 删除文件操作 */
    @Slf4j
    static class DeleteOp implements FlagOperation {

        private String resolvePattern(String raw, ResolvedPath fileInfo) {
            String expanded = expandFileNameShortCodes(raw);
            if (fileInfo != null && !expanded.startsWith("/")) {
                return normalizePath(fileInfo.resolveRelative(
                        TransferSupport.expandPathVariables(expanded, fileInfo)));
            }
            if (fileInfo != null) {
                return TransferSupport.expandPathVariables(expanded, fileInfo);
            }
            return expanded;
        }

        @Override
        public void execute(FtpClient client, String[] parts) throws Exception {
            execute(client, parts, null, null, null, null);
        }

        @Override
        public void execute(FtpClient client, String[] parts, ResolvedPath fileInfo,
                            Map<String, String> extraValues,
                            String categoryCode, String controlCode) throws Exception {
            String pattern = parts.length > 1 ? parts[1].trim() : "";
            if (pattern.isEmpty()) return;
            pattern = resolvePattern(pattern, fileInfo);
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

        private String resolvePattern(String raw, ResolvedPath fileInfo) {
            String expanded = expandFileNameShortCodes(raw);
            if (fileInfo != null && !expanded.startsWith("/")) {
                return normalizePath(fileInfo.resolveRelative(
                        TransferSupport.expandPathVariables(expanded, fileInfo)));
            }
            if (fileInfo != null) {
                return TransferSupport.expandPathVariables(expanded, fileInfo);
            }
            return expanded;
        }

        @Override
        public void execute(FtpClient client, String[] parts) throws Exception {
            execute(client, parts, null, null, null, null);
        }

        @Override
        public void execute(FtpClient client, String[] parts, ResolvedPath fileInfo,
                            Map<String, String> extraValues,
                            String categoryCode, String controlCode) throws Exception {
            String from = parts.length > 1 ? parts[1].trim() : "";
            String to = parts.length > 2 ? parts[2].trim() : "";
            if (from.isEmpty() || to.isEmpty()) return;
            from = resolvePattern(from, fileInfo);
            to = resolvePattern(to, fileInfo);
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

    /** 发送消息操作 — 从配置取代号查消息表 */
    @Slf4j
    static class SendMessageOp implements FlagOperation {
        private final MessageSender messageSender;
        private final MessageConfigService messageConfigService;

        SendMessageOp(MessageSender messageSender, MessageConfigService messageConfigService) {
            this.messageSender = messageSender;
            this.messageConfigService = messageConfigService;
        }

        @Override
        public void execute(FtpClient client, String[] parts) throws Exception {
            execute(client, parts, null, null, null, null);
        }

        @Override
        public void execute(FtpClient client, String[] parts, ResolvedPath fileInfo,
                            Map<String, String> extraValues,
                            String categoryCode, String controlCode) throws Exception {
            if (categoryCode == null || controlCode == null) {
                log.warn("MSG operation skipped: no categoryCode/controlCode provided");
                return;
            }
            MessageConfig msgConfig = messageConfigService.getConfig(categoryCode, controlCode);
            if (msgConfig == null) {
                log.warn("Message config not found: {}_{}", categoryCode, controlCode);
                return;
            }
            // 展开消息模板中的占位符
            String message = expandMessageTemplate(msgConfig.getMessageTemplate(), extraValues, fileInfo);
            String targetConfig = msgConfig.getChannelType() + ":" + msgConfig.getTarget();
            messageSender.send(targetConfig, message);
        }

        private String expandMessageTemplate(String template, Map<String, String> extraValues,
                                             ResolvedPath fileInfo) {
            if (template == null) return "";
            String result = template;
            if (extraValues != null) {
                for (Map.Entry<String, String> e : extraValues.entrySet()) {
                    result = result.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
                }
            }
            if (fileInfo != null) {
                result = result.replace("{stem}", fileInfo.stem() != null ? fileInfo.stem() : "");
                result = result.replace("{name}", fileInfo.name() != null ? fileInfo.name() : "");
                result = result.replace("{file}", fileInfo.fullPath() != null ? fileInfo.fullPath() : "");
            }
            return result;
        }
    }
}