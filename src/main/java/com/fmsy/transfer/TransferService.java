package com.fmsy.transfer;

import com.fmsy.config.AppConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.lifecycle.ConfigLoaderService;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.polling.DetailPollingService;
import com.fmsy.repository.CommandRepository;
import com.fmsy.transfer.download.DownloadOrchestrator;
import com.fmsy.transfer.upload.UploadOrchestrator;
import com.fmsy.util.LogUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 传输服务 - 统一管理上下载执行的入口
 *
 * 功能说明：
 * - 调度 UploadOrchestrator 和 DownloadOrchestrator 执行具体任务
 * - 管理命令执行的生命周期
 *
 * 命令类型说明：
 * - null/SERIAL: 串行命令，同类别+控制代码的命令不能并发
 * - R/BATCH: 批量命令，使用明细表指定文件名或bucket值
 * - S/COORDINATED: 协调命令，由主命令创建，供多节点竞争执行
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final UploadOrchestrator uploadOrchestrator;
    private final DownloadOrchestrator downloadOrchestrator;
    private final ConfigLoaderService configLoader;
    private final DetailPollingService detailPollingService;
    private final AppConfig appConfig;
    private final CommandRepository commandRepository;

    /**
     * 处理命令入口
     * 根据传输方向选择上载或下载执行器
     */
    public void process(Long commandId, String direction) {
        LogUtils.setTaskId(commandId);
        LogUtils.setNodeId(appConfig.getNodeId());
        log.info("Processing command: {}, direction: {}", commandId, direction);
        try {
            // 查询命令信息和传输配置
            Command command = commandRepository.findById(commandId);
            if (command == null) {
                log.warn("Command not found: {}", commandId);
                // P0#3修复:命令中途消失(理论不应发生)也要置 ERROR + 写结果,
                // 避免状态卡 PROCESSING
                commandRepository.markErrorWithResult(commandId, null, null,
                        "Command disappeared after compete");
                return;
            }

            // P1#6修复:补 setStartTime,让结果表记入真正的处理起始时间
            command.markStartTimeIfAbsent();

            // 配置缺失时按需求 7.4.2.4 置 E + 写结果(避免命令卡 PROCESSING)
            TransferConfig config = configLoader.getConfigOrDefault(
                    command.getCategoryCode(), command.getControlCode());
            if (config == null) {
                commandRepository.markErrorWithResult(commandId,
                        command.getCategoryCode(), command.getControlCode(),
                        "Config not found: " + command.getCategoryCode() + "_" + command.getControlCode());
                return;
            }

            if (Result.DIRECTION_UPLOAD.equals(direction)) {
                uploadOrchestrator.execute(command, config);
            } else if (Result.DIRECTION_DOWNLOAD.equals(direction)) {
                // S型子命令由DetailPollingService处理
                if (command.getCommandType() == CommandType.COORDINATED) {
                    // S型子命令的extraInfo存储的是主命令ID
                    detailPollingService.pollAndProcess(appConfig.getNodeId(),
                            command.getExtraInfo(), command);
                } else {
                    downloadOrchestrator.execute(command, config);
                }
            } else {
                log.warn("Unknown direction: {} for command: {}", direction, commandId);
            }
        } catch (Exception e) {
            log.error("Failed to process command: {}", commandId, e);
        }
    }
}
