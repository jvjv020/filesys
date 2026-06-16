package com.fmsy.converter;

import com.fmsy.config.ExternalConfigLoader;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.util.ParserConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
final class XmlExternalConfigResolver {

    static final XmlExternalConfigResolver INSTANCE = new XmlExternalConfigResolver(new ExternalConfigLoader("config"));

    private final ExternalConfigLoader externalConfigLoader;

    XmlExternalConfigResolver(ExternalConfigLoader externalConfigLoader) {
        this.externalConfigLoader = externalConfigLoader;
    }

    XmlProfile resolve(FieldMapping mapping, Map<String, String> cfg) {
        TransferConfig config = mapping != null ? mapping.getConfig() : null;
        String parserType = config != null ? config.getParserType() : "XmlConverter";
        String categoryCode = config != null ? config.getCategoryCode() : null;
        String controlCode = config != null ? config.getControlCode() : null;
        Path fieldsPath = resolvePath(cfg.get("fieldsPath"))
                .or(() -> externalConfigLoader.resolveExternalPath(parserType, categoryCode, controlCode, ".fields.json"))
                .orElse(null);
        Path xsdPath = resolvePath(cfg.get("xsdPath"))
                .or(() -> externalConfigLoader.resolveExternalPath(parserType, categoryCode, controlCode, ".xsd"))
                .orElse(null);
        Path parseXslPath = resolvePath(cfg.get("parseXslPath"))
                .or(() -> externalConfigLoader.resolveExternalPath(parserType, categoryCode, controlCode, ".parse.xsl"))
                .or(() -> externalConfigLoader.resolveExternalPath(parserType, categoryCode, controlCode, ".xsl"))
                .orElse(null);
        Path generateXslPath = resolvePath(cfg.get("generateXslPath"))
                .or(() -> externalConfigLoader.resolveExternalPath(parserType, categoryCode, controlCode, ".generate.xsl"))
                .or(() -> externalConfigLoader.resolveExternalPath(parserType, categoryCode, controlCode, ".xsl"))
                .orElse(null);
        Map<String, String> fieldsJson = fieldsPath != null ? loadJson(fieldsPath) : Map.of();
        return new XmlProfile(
                cfg.getOrDefault("xmlMode", "auto"),
                cfg.getOrDefault("encoding", "UTF-8"),
                override(cfg.get("rootElement"), fieldsJson.get("rootElement"), "data"),
                override(cfg.get("recordElement"), fieldsJson.get("recordElement"), "record"),
                Boolean.parseBoolean(override(cfg.get("fieldAsAttribute"), fieldsJson.get("fieldAsAttribute"), "false")),
                fieldPathsFrom(fieldsJson),
                xsdPath,
                parseXslPath,
                generateXslPath);
    }

    private static String override(String cfgValue, String fileValue, String fallback) {
        if (fileValue != null && !fileValue.isBlank()) return fileValue;
        if (cfgValue != null && !cfgValue.isBlank()) return cfgValue;
        return fallback;
    }

    private Optional<Path> resolvePath(String value) {
        if (value == null || value.isBlank() || value.contains("..")) {
            return Optional.empty();
        }
        Path path = Paths.get(value).normalize();
        return Files.exists(path) ? Optional.of(path) : Optional.empty();
    }

    private Map<String, String> loadJson(Path path) {
        String content = externalConfigLoader.readExternalPath(path);
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        Map<String, String> parsed = ParserConfigUtil.parseJson(content);
        log.info("Loaded XML fields.json {} with {} entries", path, parsed.size());
        return parsed;
    }

    private static Map<String, String> fieldPathsFrom(Map<String, String> fieldsJson) {
        Map<String, String> paths = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fieldsJson.entrySet()) {
            String key = entry.getKey();
            if ("rootElement".equals(key) || "recordElement".equals(key) || "fieldAsAttribute".equals(key)
                    || "xmlMode".equals(key) || "encoding".equals(key)
                    || "xsdPath".equals(key) || "parseXslPath".equals(key) || "generateXslPath".equals(key)
                    || "fieldsPath".equals(key)) {
                continue;
            }
            if (!key.isBlank() && entry.getValue() != null && !entry.getValue().isBlank()) {
                paths.put(key, entry.getValue());
            }
        }
        return paths;
    }

    record XmlProfile(String xmlMode,
                      String encoding,
                      String rootElement,
                      String recordElement,
                      boolean fieldAsAttribute,
                      Map<String, String> fieldPaths,
                      Path xsdPath,
                      Path parseXslPath,
                      Path generateXslPath) {
        boolean hasParseXsl() {
            return parseXslPath != null;
        }

        boolean hasGenerateXsl() {
            return generateXslPath != null;
        }

        boolean useParseXsl() {
            return hasParseXsl() && !"stream".equalsIgnoreCase(xmlMode);
        }

        boolean useGenerateXsl() {
            return hasGenerateXsl() && !"stream".equalsIgnoreCase(xmlMode);
        }
    }
}
