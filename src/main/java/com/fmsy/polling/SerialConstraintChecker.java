package com.fmsy.polling;

import com.fmsy.config.AppConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.lifecycle.ConfigLoaderService;
import com.fmsy.model.Command;
import com.fmsy.model.TransferConfig;
import com.fmsy.util.BooleanUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 串行约束检查器
 *
 * 功能说明：
 * - 检查同类命令是否允许并发处理
 * - 根据serialFlag配置决定是否需要串行执行
 *
 * 串行约束规则：
 * - serialFlag=Y: 相同类别+控制代号的命令必须串行执行
 * - serialFlag=N或未配置: 相同节点不能重复处理，但不同节点可以并发
 *
 * 特殊情况：
 * - S型子命令：如果同属一个主命令，允许并发处理
 * - 不同主命令的S型子命令之间需要检查串行约束
 * - T类型临时指令：不参与串行约束，直接放行
 */
@Component
@RequiredArgsConstructor
public class SerialConstraintChecker {

    private final ConfigLoaderService configLoader;
    private final AppConfig appConfig;

    /**
     * 检查命令是否可以执行
     * @param command 待检查的命令
     * @param processingMap 当前正在处理的任务映射
     * @return true=允许执行，false=应等待
     */
    public boolean check(Command command, Map<String, CommandProcessingTracker> processingMap) {
        // T 类型临时指令不参与串行约束
        if (command.getCommandType() == CommandType.TEMPORARY) {
            return true;
        }
        String currentNodeId = appConfig.getNode().getId();

        // S型子命令特殊处理
        if (command.getCommandType() == CommandType.COORDINATED) {
            String key = command.getCategoryCode() + "_" + command.getControlCode();
            CommandProcessingTracker info = processingMap.get(key);
            if (info != null && info.isHasSType()) {
                String mainCommandId = command.getExtraInfo();
                if (mainCommandId != null && info.getMainCommandId() != null
                        && mainCommandId.equals(info.getMainCommandId())) {
                    // 同主命令的子命令，允许并发
                } else {
                    // 同节点已有该主命令的指令（主指令或同主S指令），豁免串行约束
                    if (info.hasMainId(currentNodeId, mainCommandId)) {
                        // 允许并发
                    } else {
                        return false; // 不同主命令，需要等待
                    }
                }
            }
        }

        String key = command.getCategoryCode() + "_" + command.getControlCode();
        TransferConfig config = configLoader.getConfig(command.getCategoryCode(), command.getControlCode());
        if (config == null) {
            return true; // 无配置，允许执行
        }

        boolean serial = BooleanUtils.isYes(config.getSerialFlag());
        CommandProcessingTracker info = processingMap.get(key);

        if (info == null) {
            return true; // 无正在执行的同类命令
        }

        if (serial) {
            return info.getNodes().isEmpty(); // 串行模式：检查是否有节点在处理
        } else {
            // 非串行模式：检查同节点是否在处理，使用当前节点ID
            return currentNodeId == null || !info.hasNode(currentNodeId);
        }
    }
}