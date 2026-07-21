package com.fmsy.transfer.upload;

import com.fmsy.ftp.FtpClient;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ResolvedPath;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 上传预扫描工具类 — 在单次 FTP 列表结果中按 flag/glob 模式筛选有效数据文件。
 *
 * <p>
 * 无状态静态方法集，纯函数式工具，供 {@link MultiUploadHandler} 等调用方使用。
 * </p>
 *
 * <p>
 * 提供两类筛选：
 * <ul>
 * <li><b>flag 模式</b>：数据文件须有对应标志文件（如 {stem}.ok）才算有效</li>
 * <li><b>glob 模式</b>：无 flag 模式时按文件名通配符做二次过滤</li>
 * </ul>
 * </p>
 *
 * <p>
 * 流程：一次遍历分离数据文件和标志文件 → 交叉筛选（匹配对应标志） → 处理孤立标志文件
 * </p>
 */
@Slf4j
public final class UploadPrescanner {

    /** {xxx} 花括号文件衍生变量的合法名称 */
    private static final Set<String> KNOWN_VARS = Set.of("stem", "name", "ext", "dir", "dn", "up");

    private UploadPrescanner() {
    }

    // ==================== 正则转换 ====================

    /**
     * 将标志文件名模式转换为正则表达式，用于匹配标志文件。
     *
     * <p>
     * 已知变量（{stem}、{name}、{ext}、{dir}、{dn}、{up}）替换为 {@code [^/]+?}，
     * 字面量部分做 {@link Pattern#quote} 转义，避免正则元字符冲突。
     * </p>
     *
     * <p>
     * 示例：
     * <ul>
     * <li>{@code {stem}.ok} → {@code ^[^/]+?\.ok$}</li>
     * <li>{@code {name}.ok} → {@code ^[^/]+?\.ok$}</li>
     * <li>{@code filename.ok} → {@code ^filename\.ok$}</li>
     * <li>{@code flag_{stem}.ok} → {@code ^flag_[^/]+?\.ok$}</li>
     * </ul>
     *
     * @param flagPattern 标志文件名模式，如 "{stem}.ok"；null 返回 null
     * @return 正则 Pattern；null 输入返回 null
     */
    static Pattern toFlagRegex(String flagPattern) {
        if (flagPattern == null)
            return null;
        // 按变量边界分割，保留变量作为独立元素
        String[] parts = flagPattern.split("(?=\\{)|(?<=\\})", -1);
        StringBuilder sb = new StringBuilder("^");
        for (String part : parts) {
            if (isBracketVariable(part)) {
                sb.append("[^/]+?");
            } else {
                sb.append(Pattern.quote(part));
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }

    /** 判断字符串是否为 {xxx} 形式的文件衍生变量 */
    private static boolean isBracketVariable(String s) {
        return s != null && s.length() > 2
                && s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}'
                && KNOWN_VARS.contains(s.substring(1, s.length() - 1));
    }

    /**
     * 将 glob 通配符模式转为正则，用于无 flag 模式时对数据文件做二次过滤。
     *
     * <p>
     * 示例：{@code BR*.csv} → {@code ^BR[^/]*\.csv$}
     * </p>
     *
     * @param glob glob 通配符模式（如 "BR*.csv"），null 返回 null
     * @return 正则 Pattern，仅匹配文件名部分（不含目录）
     */
    static Pattern globToRegex(String glob) {
        if (glob == null)
            return null;
        // 提取文件名部分（glob 通常是完整路径，如 /data/BR*.csv）
        int lastSlash = glob.lastIndexOf('/');
        String pattern = lastSlash >= 0 ? glob.substring(lastSlash + 1) : glob;
        // 若去掉目录后无通配符，说明不含 {FILE_NAME} 占位符，无需过滤
        if (pattern.isEmpty())
            return null;

        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                sb.append("[^/]*");
            } else if (c == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }

    // ==================== 标志文件名解析 ====================

    /**
     * 根据标志文件模式和数据文件信息，解析出对应的标志文件名。
     *
     * <p>
     * 先对模式做文件衍生变量展开（{stem}→file, {name}→file.csv, ...），
     * 再通过 ResolvedPath 做相对路径继承处理。
     * </p>
     *
     * @param flagPattern 标志文件名模式，如 "{stem}.ok"
     * @param fileInfo    数据文件路径信息
     * @return 解析后的标志文件完整路径；任一参数为 null 时返回 null
     */
    static String resolveFlagName(String flagPattern, ResolvedPath fileInfo) {
        if (flagPattern == null || fileInfo == null)
            return null;
        String resolved = TransferSupport.expandPathVariables(flagPattern, fileInfo);
        return fileInfo.resolveRelative(resolved);
    }

    // ==================== 预扫描筛选核心 ====================

    /**
     * 从文件列表中筛选有效数据文件（只做 flag 模式过滤）。
     *
     * <p>
     * 委托给 {@link #prescanDataFiles(String[], String, Pattern, FtpClient)}，
     * 不传 listRegex 和 client，跳过 glob 二次过滤和孤立标志迁移。
     * </p>
     *
     * @param allFiles    FTP 目录列表的全部文件名
     * @param flagPattern 标志文件名模式，null 表示无 flag 模式
     * @return 有效数据文件列表
     */
    static List<String> prescanDataFiles(String[] allFiles, String flagPattern) {
        return prescanDataFiles(allFiles, flagPattern, null, null);
    }

    /**
     * 从文件列表中筛选有效数据文件，并处理孤立标志文件。
     *
     * <p>
     * 流程：
     * <ol>
     * <li>一次遍历分类：将文件分为数据文件和标志文件</li>
     * <li>无 flag 模式时直接返回所有数据文件</li>
     * <li>遍历数据文件，有对应标志的加入有效列表，并从已匹配集合中移除该标志名</li>
     * <li>剩余的标志即为孤立标志（无对应数据文件），迁到 error 目录</li>
     * </ol>
     * </p>
     *
     * @param allFiles    FTP 目录列表的全部文件名
     * @param flagPattern 标志文件名模式，null 表示无 flag 模式
     * @param listRegex   数据文件命名正则，用于过滤额外文件（如 glob→regex）；null 表示不过滤
     * @param client      FTP 客户端（非 null 时执行孤立标志文件迁移）
     * @return 有效数据文件列表（含目录的完整路径）
     */
    static List<String> prescanDataFiles(String[] allFiles, String flagPattern,
            Pattern listRegex, FtpClient client) {
        if (allFiles == null || allFiles.length == 0)
            return List.of();

        // 一次遍历分离数据文件和标志文件
        Pattern flagRegex = flagPattern != null ? toFlagRegex(flagPattern) : null;
        List<String> dataFiles = new ArrayList<>();
        Set<String> flagNames = new HashSet<>();
        Map<String, String> flagFilePaths = new HashMap<>();

        for (String f : allFiles) {
            String name = ResolvedPath.of(f).name();
            boolean isFlag = flagRegex != null && flagRegex.matcher(name).matches();
            boolean isData = listRegex == null || listRegex.matcher(name).matches();

            if (isFlag) {
                flagNames.add(name);
                flagFilePaths.put(name, f);
            }
            if (isData && !isFlag) {
                dataFiles.add(f);
            }
        }

        // 无 flag 模式，所有数据文件即为有效
        if (flagPattern == null)
            return dataFiles;

        // 交叉筛选：有对应标志的加入有效列表，并从 flagNames 中移除
        List<String> validDataFiles = new ArrayList<>();
        for (String f : dataFiles) {
            ResolvedPath fileInfo = ResolvedPath.of(f);
            String expectedFlagName = resolveFlagName(flagPattern, fileInfo);
            ResolvedPath expectedPath = ResolvedPath.of(expectedFlagName);
            String expectedFlagFileName = expectedPath != null ? expectedPath.name() : null;

            if (flagNames.remove(expectedFlagFileName)) {
                validDataFiles.add(f);
            } else {
                log.warn("Data file {} has no corresponding flag file (expected: {}), skipping",
                        f, expectedFlagName);
            }
        }

        // 迁移孤立标志文件到 error 目录
        if (client != null) {
            int orphanedCount = 0;
            for (String name : flagNames) {
                String fullPath = flagFilePaths.get(name);
                log.warn("Orphaned flag file, moving to error: {}", fullPath);
                try {
                    client.moveToErrorDir(fullPath);
                    orphanedCount++;
                } catch (Exception e) {
                    log.error("Failed to move orphaned flag to error: {}", fullPath, e);
                }
            }
            if (orphanedCount > 0) {
                log.info("Moved {} orphaned flag(s) to error dir", orphanedCount);
            }
        }
        return validDataFiles;
    }

}
