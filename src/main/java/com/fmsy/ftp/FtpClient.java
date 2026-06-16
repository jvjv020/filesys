package com.fmsy.ftp;

import com.fmsy.util.FilePathUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * FTP客户端封装类
 *
 * 功能说明：
 * - 封装Apache FTPClient，提供便捷的文件操作接口
 * - 与FtpPool配合使用，实现连接池化管理
 * - 自动处理连接失败重试和连接标记
 *
 * 使用方式：
 * - 通过FtpPool.getClient()获取实例
 * - 操作完成后调用close()归还连接池
 * - 不要直接调用disconnect()
 *
 * 主要操作：
 * - getInputStream: 下载文件内容
 * - getOutputStream: 上传文件内容
 * - exists: 检查文件是否存在
 * - mkdirs: 创建目录（支持多级）
 * - rename: 重命名文件
 * - deleteFile: 删除文件
 * - listFiles: 列出目录文件
 */
@Slf4j
public class FtpClient {

    private final FTPClient client;
    private final FtpPool.FtpConfigHolder holder;

    FtpClient(FTPClient client, FtpPool.FtpConfigHolder holder) {
        this.client = client;
        this.holder = holder;
    }

    FTPClient getClient() {
        return client;
    }

    /**
     * 下载文件获取输入流
     * @param path FTP文件路径
     * @return 输入流，读取文件内容
     */
    public InputStream getInputStream(String path) throws IOException {
        FilePathUtils.validatePath(path);
        try {
            return client.retrieveFileStream(path);
        } catch (IOException e) {
            handleConnectionFailure();
            throw e;
        }
    }

    /**
     * 上传文件获取输出流
     * @param path 目标FTP文件路径
     * @return 输出流，写入即上传
     */
    public OutputStream getOutputStream(String path) throws IOException {
        FilePathUtils.validatePath(path);
        try {
            return client.storeFileStream(path);
        } catch (IOException e) {
            handleConnectionFailure();
            throw e;
        }
    }

    /** 连接失败处理 - 从池中移除失效连接(失败一次即销毁,不再复用) */
    private void handleConnectionFailure() {
        log.error("FTP connection failed, removing dead client from pool");
        holder.removeClient(client);
    }

    /**
     * 检查文件是否存在
     * @param path 文件路径
     * @return true=存在
     */
    public boolean exists(String path) {
        try {
            String dir = FilePathUtils.extractDirectory(path);
            String name = FilePathUtils.extractFileName(path);
            if (!client.changeWorkingDirectory(dir)) {
                log.debug("exists: changeWorkingDirectory failed for {}", dir);
                return false;
            }
            return fileExists(name);
        } catch (IOException e) {
            handleConnectionFailure();
            log.error("Failed to check file existence: {}", path, e);
            return false;
        }
    }

    private boolean fileExists(String fileName) throws IOException {
        String[] files = client.listNames();
        if (files != null) {
            for (String file : files) {
                if (file.equals(fileName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 创建目录（支持多级目录）
     * @param path 目录路径
     * @return true=创建成功
     */
    public boolean mkdirs(String path) {
        FilePathUtils.validatePath(path);
        try {
            String[] parts = path.split("/");
            StringBuilder current = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                current.append("/").append(part);
                if (!client.changeWorkingDirectory(current.toString())) {
                    if (!client.makeDirectory(current.toString())) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException e) {
            handleConnectionFailure();
            log.error("Failed to create directories: {}", path, e);
            return false;
        }
    }

    /**
     * 重命名文件
     * @param from 原路径
     * @param to 目标路径
     * @return true=成功
     */
    public boolean rename(String from, String to) {
        FilePathUtils.validatePath(from);
        FilePathUtils.validatePath(to);
        try {
            return client.rename(from, to);
        } catch (IOException e) {
            handleConnectionFailure();
            log.error("Failed to rename file: {} -> {}", from, to, e);
            return false;
        }
    }

    /** 完成待处理的FTP命令（用于流式上传/下载后） */
    public void completePendingCommand() {
        try {
            client.completePendingCommand();
        } catch (IOException e) {
            handleConnectionFailure();
            log.error("Failed to complete pending command", e);
        }
    }

    /**
     * 列出目录下匹配的文件
     * @param path 路径（支持通配符如 dir/*）
     * @return 文件名数组
     */
    public String[] listFiles(String path) {
        FilePathUtils.validatePath(path);
        try {
            int lastSlash = path.lastIndexOf('/');
            String dir = lastSlash >= 0 ? path.substring(0, lastSlash) : ".";
            String pattern = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;

            if (!client.changeWorkingDirectory(dir)) {
                log.warn("listFiles: changeWorkingDirectory failed for {}", dir);
                return new String[0];
            }

            String[] files = client.listNames(pattern);
            return files != null ? files : new String[0];
        } catch (IOException e) {
            handleConnectionFailure();
            log.error("Failed to list files: {}", path, e);
            return new String[0];
        }
    }

    /**
     * 删除文件
     * @param path 文件路径
     * @return true=成功
     */
    public boolean deleteFile(String path) {
        FilePathUtils.validatePath(path);
        try {
            return client.deleteFile(path);
        } catch (IOException e) {
            handleConnectionFailure();
            log.error("Failed to delete file: {}", path, e);
            return false;
        }
    }

    /** 关闭客户端（归还连接到池） */
    public void close() {
        holder.returnClient(this);
    }

    /**
     * P2#4 UC-14:错误文件处理 —— 把源文件移动到同层 error 目录。
     *
     * <p>目标路径规则: <code>&lt;源文件目录&gt;/error/&lt;原文件名&gt;_&lt;yyyyMMddHHmmss&gt;</code>
     * 同时尝试移动同名标志文件(.OK / .ready / .flag)。
     *
     * @param sourcePath 失败文件的 FTP 全路径
     * @return 移动后的目标路径;若失败返回 null
     */
    public String moveToErrorDir(String sourcePath) {
        FilePathUtils.validatePath(sourcePath);
        String dir = FilePathUtils.extractDirectory(sourcePath);
        String name = FilePathUtils.extractFileName(sourcePath);
        String errorDir = dir + "/error";
        if (!mkdirs(errorDir)) {
            log.warn("UC-14: cannot create error dir {} for {}", errorDir, sourcePath);
            return null;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String targetName = stem + "_" + timestamp + ext;
        String targetPath = errorDir + "/" + targetName;
        if (!rename(sourcePath, targetPath)) {
            log.warn("UC-14: rename failed for {} -> {}", sourcePath, targetPath);
            return null;
        }
        log.info("UC-14: moved error file {} -> {}", sourcePath, targetPath);
        // 同步移动同名的标志文件(常见的 .OK / .ready / .flag)
        // 标志文件操作为 best-effort,异常不传播、不移除连接
        for (String suffix : List.of(".OK", ".ok", ".ready", ".flag")) {
            String flagPath = sourcePath + suffix;
            try {
                if (exists(flagPath)) {
                    String flagTarget = errorDir + "/" + FilePathUtils.extractFileName(flagPath)
                            .replaceFirst("(\\.[^.]+)$", "_" + timestamp + "$1");
                    rename(flagPath, flagTarget);
                }
            } catch (Exception e) {
                log.warn("UC-14: failed to move flag file {}: {}", flagPath, e.getMessage());
            }
        }
        return targetPath;
    }

    /**
     * 统计文件行数
     * @param filePath 文件路径
     * @return 行数
     */
    public int countFileLines(String filePath) throws IOException {
        FilePathUtils.validatePath(filePath);
        int lineCount = 0;
        try (InputStream is = getInputStream(filePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                lineCount++;
            }
        }
        return lineCount;
    }

    /**
     * 获取文件大小（字节）
     *
     * <p>P0 #4(UC-08):用于 CHECK_FLAG SIZE 模式计算数据文件实际值。
     *
     * @param filePath 文件路径
     * @return 文件大小（字节），文件不存在或获取失败返回 -1
     */
    public long getFileSize(String filePath) throws IOException {
        FilePathUtils.validatePath(filePath);
        FTPFile file = client.mlistFile(filePath);
        if (file == null) {
            // 部分 FTP 服务器不支持 MLST,降级到 LIST
            String dir = FilePathUtils.extractDirectory(filePath);
            String name = FilePathUtils.extractFileName(filePath);
            if (!client.changeWorkingDirectory(dir)) return -1;
            FTPFile[] list = client.listFiles(name);
            if (list == null || list.length == 0) return -1;
            return list[0].getSize();
        }
        return file.getSize();
    }

    /**
     * 计算文件 MD5 值。
     *
     * <p>用于 FLAG mode 校验和内容模式码 M。
     * 读取文件内容后通过 java.security.MessageDigest 计算 MD5 十六进制字符串。
     *
     * @param filePath 文件路径
     * @return MD5 十六进制小写字符串，失败返回 ""
     */
    public String computeMd5(String filePath) {
        try {
            FilePathUtils.validatePath(filePath);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            try (InputStream is = getInputStream(filePath)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) >= 0) {
                    md.update(buf, 0, len);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to compute MD5 for file: {}", filePath, e);
            return "";
        }
    }
}