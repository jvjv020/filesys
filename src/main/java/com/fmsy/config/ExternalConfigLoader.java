package com.fmsy.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

/**
 * 外部配置加载器 - 从 config/{format}/{categoryCode}_{controlCode} 加载配置内容
 *
 * 用途:
 * - 配合 TransferConfig.parserConfig 字段(JSON)实现配置分级
 * - 支持 XSL 模板、复杂 CSV 解析规则等无法在数据库 JSON 字段中存储的复杂配置
 *
 * 目录约定:
 * - 默认根目录: ${fmsy.config.dir:config},可通过 fmsy.config.dir 配置项覆盖
 * - 子目录: 归一化后的 parserType(小写)
 * - 文件名: {categoryCode}_{controlCode} 允许带常见扩展名(.json/.xml/.xsl/.csv/.txt)
 */
@Slf4j
@Component
public class ExternalConfigLoader {

    private final String baseDir;

    public ExternalConfigLoader(@Value("${fmsy.config.dir:config}") String baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * 加载外部配置内容
     *
     * @param parserType   解析器类型(如 DbfConverter / XmlConverter)
     * @param categoryCode 类别代号
     * @param controlCode  控制代号
     * @return 配置文件内容,不存在或异常时返回 null
     */
    public String loadExternalConfig(String parserType, String categoryCode, String controlCode) {
        if (categoryCode == null || controlCode == null) {
            return null;
        }
        String format = normalizeFormat(parserType);
        String filename = categoryCode + "_" + controlCode;
        try {
            // 尝试无扩展名 + 常见扩展名变体
            for (String ext : new String[]{"", ".json", ".xml", ".xsl", ".csv", ".txt"}) {
                Path path = Paths.get(baseDir, format, filename + ext);
                if (Files.exists(path)) {
                    return Files.readString(path, StandardCharsets.UTF_8);
                }
            }
            return null;
        } catch (IOException e) {
            log.warn("Failed to load external config for {}_{} under {}/{}: {}",
                categoryCode, controlCode, baseDir, format, e.getMessage());
            return null;
        }
    }

    public Optional<Path> resolveExternalPath(String parserType, String categoryCode, String controlCode, String suffix) {
        if (categoryCode == null || controlCode == null || suffix == null || suffix.contains("..")) {
            return Optional.empty();
        }
        String format = normalizeFormat(parserType);
        Path path = Paths.get(baseDir, format, categoryCode + "_" + controlCode + suffix).normalize();
        Path root = Paths.get(baseDir).normalize();
        if (!path.startsWith(root)) {
            return Optional.empty();
        }
        return Files.exists(path) ? Optional.of(path) : Optional.empty();
    }

    public String readExternalPath(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read external config {}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 归一化 parserType 为目录名
     * <ul>
     *   <li>DbfConverter -> dbf</li>
     *   <li>XmlConverter -> xml</li>
     *   <li>CsvConverter -> csv</li>
     *   <li>TxtConverter -> txt</li>
     *   <li>null -> default</li>
     * </ul>
     */
    private String normalizeFormat(String parserType) {
        if (parserType == null) {
            return "default";
        }
        String s = parserType.endsWith("Converter")
            ? parserType.substring(0, parserType.length() - "Converter".length())
            : parserType;
        return s.toLowerCase(Locale.ROOT);
    }
}
