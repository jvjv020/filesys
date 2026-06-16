package com.fmsy.util;

/**
 * 文件路径解析值对象 — filePath 模板解析完成后产出的衍生信息。
 *
 * <p>供 PlaceholderResolver 和 FlagFileService 使用，让前置/后置操作
 * 可以通过 {stem}/{name}/{ext}/{dir}/{dn}/{up} 变量引用数据文件的各段。
 *
 * <pre>
 * 示例: fullPath = /data/export/BR001/data_20260615.csv
 *   stem → data_20260615
 *   name → data_20260615.csv
 *   ext  → .csv
 *   dir  → /data/export/BR001
 *   dn   → BR001
 *   up   → /data/export
 * </pre>
 */
public record ResolvedPath(
        String fullPath,
        String stem,
        String name,
        String ext,
        String dir,
        String dn,
        String up) {

    /**
     * 从完整路径解析出各衍生字段。
     *
     * @param fullPath 完整文件路径，可为 null
     * @return ResolvedPath，null 输入返回 null
     */
    public static ResolvedPath of(String fullPath) {
        if (fullPath == null || fullPath.isEmpty()) return null;

        int lastSlash = fullPath.lastIndexOf('/');
        String name = lastSlash >= 0 ? fullPath.substring(lastSlash + 1) : fullPath;
        String dir = lastSlash >= 0 ? fullPath.substring(0, lastSlash) : "";

        int lastDot = name.lastIndexOf('.');
        String stem = lastDot > 0 ? name.substring(0, lastDot) : name;
        String ext = lastDot > 0 ? name.substring(lastDot) : "";

        int upSlash = dir.lastIndexOf('/');
        String dn = upSlash >= 0 ? dir.substring(upSlash + 1) : dir;
        String up = upSlash >= 0 ? dir.substring(0, upSlash) : "";

        return new ResolvedPath(fullPath, stem, name, ext, dir, dn, up);
    }

    /**
     * 将非绝对路径转为绝对路径。
     * 不以 "/" 开头时，自动添加 "{dir}/" 前缀。
     *
     * @param pattern 路径模板
     * @return 如果 pattern 以 "/" 开头则原样返回，否则返回 dir + "/" + pattern
     */
    public String resolveRelative(String pattern) {
        if (pattern == null) return null;
        if (pattern.startsWith("/")) return pattern;
        return dir + "/" + pattern;
    }
}
