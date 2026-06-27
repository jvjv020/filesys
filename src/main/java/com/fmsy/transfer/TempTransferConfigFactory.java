package com.fmsy.transfer;

import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.enums.TransferScenario;
import com.fmsy.exception.TransferException;
import com.fmsy.model.Command;
import com.fmsy.model.TransferConfig;
import com.fmsy.util.ParserConfigUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 临时指令配置工厂 — 将 T 类型指令的 temp_config JSON 解析为 TransferConfig 实体。
 *
 * <p>由 {@link com.fmsy.polling.BatchDispatcher} / {@link TransferService} 在检测到
 * {@link com.fmsy.enums.CommandType#TEMPORARY} 时调用。
 * S 子命令回溯 T 类型主命令时也通过本工厂构建配置。
 *
 * <p>JSON 字段映射见 docs/superpowers/specs/2026-06-16-temp-command-design.md §3.6。
 */
@Slf4j
@Component
public class TempTransferConfigFactory {

    /**
     * 从 command.tempConfig JSON 构建 TransferConfig。
     *
     * @param command 指令（应为 TEMPORARY 类型）
     * @return 完整的 TransferConfig
     * @throws TransferException JSON 解析失败或缺少必填字段时抛出
     */
    public TransferConfig build(Command command) {
        String json = command.getTempConfig();
        if (json == null || json.trim().isEmpty()) {
            throw new TransferException("TEMP_MISSING_FIELD",
                    "temp_config is null or empty for command: " + command.getId());
        }
        Map<String, String> map = ParserConfigUtil.parseJson(json);
        if (map.isEmpty()) {
            throw new TransferException("TEMP_PARSE_FAILED",
                    "Failed to parse temp_config JSON for command: " + command.getId());
        }

        TransferConfig config = new TransferConfig();
        config.setCategoryCode(command.getCategoryCode());
        config.setControlCode(command.getControlCode());

        // 必填字段
        config.setScenario(requireEnum(map, "scenario", TransferScenario.class, command.getId()));
        config.setDbName(require(map, "dbName", command.getId()));
        config.setTableName(require(map, "tableName", command.getId()));
        config.setFtpName(require(map, "ftpName", command.getId()));
        config.setFilePath(require(map, "filePath", command.getId()));
        config.setParserType(require(map, "parserType", command.getId()));

        // 可选字段（有默认值）
        config.setClearTableFlag(map.getOrDefault("clearTableFlag", "N"));
        config.setOverwriteFlag(map.getOrDefault("overwriteFlag", "Y"));
        config.setSerialFlag(map.getOrDefault("serialFlag", "N"));

        // 可选字段（无默认值，可为 null）
        config.setNodeId(map.get("nodeId"));
        config.setParserConfig(map.get("parserConfig"));
        config.setPreOperations(map.get("preOperations"));
        config.setPostOperations(map.get("postOperations"));
        config.setIgnoreFields(map.get("ignoreFields"));
        config.setSplitFields(map.get("splitFields"));

        // 可选整数
        String concurrencyStr = map.get("concurrency");
        if (concurrencyStr != null && !concurrencyStr.isEmpty()) {
            try {
                config.setConcurrency(Integer.parseInt(concurrencyStr));
            } catch (NumberFormatException e) {
                log.warn("Invalid concurrency value '{}' for command {}, using null", concurrencyStr, command.getId());
            }
        }

        // 可选枚举
        String emptyStr = map.get("emptyDataHandling");
        if (emptyStr != null && !emptyStr.isEmpty()) {
            try {
                config.setEmptyDataHandling(EmptyDataHandling.valueOf(emptyStr));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid emptyDataHandling '{}' for command {}, using ALLOW", emptyStr, command.getId());
                config.setEmptyDataHandling(EmptyDataHandling.ALLOW);
            }
        } else {
            config.setEmptyDataHandling(EmptyDataHandling.ALLOW);
        }

        log.debug("Built TransferConfig from temp_config for command: {}", command.getId());
        return config;
    }

    private static String require(Map<String, String> map, String key, Long commandId) {
        String val = map.get(key);
        if (val == null || val.trim().isEmpty()) {
            throw new TransferException("TEMP_MISSING_FIELD",
                    "Missing required field '" + key + "' in temp_config for command: " + commandId);
        }
        return val.trim();
    }

    private static <T extends Enum<T>> T requireEnum(Map<String, String> map, String key,
                                                      Class<T> enumClass, Long commandId) {
        String val = require(map, key, commandId);
        try {
            return Enum.valueOf(enumClass, val);
        } catch (IllegalArgumentException e) {
            throw new TransferException("TEMP_INVALID_ENUM",
                    "Invalid " + key + "='" + val + "' for command: " + commandId, e);
        }
    }
}
