package com.fmsy.util;

/**
 * 文件路径工具类
 *
 * 功能说明：
 * - 从路径中提取目录
 * - 从路径中提取文件名
 * - 拼接目录和文件名
 *
 * 支持Windows和Unix两种路径分隔符
 */
public final class FilePathUtils {

    private FilePathUtils() {
    }

    /** 从路径中提取目录部分 */
    public static String extractDirectory(String path) {
        validatePath(path);
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash > 0 ? path.substring(0, lastSlash) : ".";
    }

    /** 从路径中提取文件名 */
    public static String extractFileName(String path) {
        validatePath(path);
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /** 从路径中提取父目录 */
    public static String extractParentDirectory(String path) {
        validatePath(path);
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash > 0 ? path.substring(0, lastSlash) : null;
    }

    /** 验证路径是否安全（防止路径遍历攻击） */
    private static void validatePath(String path) {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        // 统一路径分隔符后再检查，防止绕过
        String normalized = path.replace('\\', '/');
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Path traversal not allowed: " + path);
        }
    }
}