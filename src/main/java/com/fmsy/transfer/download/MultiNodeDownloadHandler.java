package com.fmsy.transfer.download;

import com.fmsy.enums.CommandType;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DOWNLOAD_MULTI_NODE 场景:创建 S 型子命令供各节点竞争处理,然后由异步的
 * SplitFlowService(分桶) + MergeFlowService(合并) + ChildBucketProcessor(子节点处理)完成全过程。
 *
 * <ul>
 *   <li>BATCH 模式:复用明细表已存在的桶,更新每个桶的 auditCount,再创建 S 子命令</li>
 *   <li>SERIAL 模式:调用 SplitFlowService 按 PK 范围切分桶(写入 specName),再创建 S 子命令</li>
 *   <li>两种模式均启动 MergeFlowService 异步合并临时文件到目标文件</li>
 *   <li>成功:result.markChildrenCreated()(主命令保持 PROCESSING,
 *       合并完成回调由此处通过 onSuccess 定义,置主命令为 Y)</li>
 *   <li>失败:result.markChildrenFailed(reason)(主命令置 ERROR)</li>
 * </ul>
 *
 * <p>合并流程的终态更新(主命令 Y/E)通过 onSuccess/onError 回调定义在本 Handler 中,
 * 使异步流程的状态转移在主体代码中可见,便于排查。</p>
 *
 * <p>子命令的并发执行由 {@link ChildBucketProcessor} 接管(本 Handler 不参与)。
 *
 * <p>preCheck 用短生命周期 FTP 连接,完成后立即归还;
 * createChildren 为纯 DB 操作时不持有 FTP 连接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiNodeDownloadHandler implements TransferHandler {

    private final BucketDistributor bucketDistributor;
    private final TransferSupport transferSupport;
    private final SplitFlowService splitFlowService;
    private final MergeFlowService mergeFlowService;
    private final CommandRepository commandRepository;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Multi-node coordinated download: {}", command.getId());

        ResolvedPath baseFileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);
        String baseFilePath = baseFileInfo != null ? baseFileInfo.fullPath() : null;
        String splitFields = config.getSplitFields();
        if (splitFields == null || splitFields.isEmpty()) {
            log.warn("No split fields configured for DOWNLOAD_MULTI_NODE");
            result.markChildrenFailed("No split fields configured");
            return;
        }

        // Phase 1: preCheck with short-lived FTP client
        boolean preCheckOk = transferSupport.executeWithClient(config.getFtpName(), client -> {
            if (!transferSupport.preCheck(client, config, baseFileInfo)) {
                result.markChildrenFailed("Pre-check failed");
                return false;
            }
            return true;
        });
        if (!preCheckOk) return;

        // Phase 2: DB-only work (no FTP client held)
        CommandType commandType = command.getCommandType();
        Long cmdId = command.getId();
        if (commandType == CommandType.BATCH) {
            // BATCH 模式:复用明细表已有桶,统计审计数后创建 S 子命令
            int childCount = bucketDistributor.prepareBatchChildren(command, config, baseFilePath, splitFields);
            // BATCH 模式的桶已预先存在,prepareBatchChildren 完成后等价于"拆分完成",
            // 标记 splitDone 使 MergeFlowService.isMergeDone 能正常退出
            commandRepository.markSplitDone(command.getId());

            if (childCount > 0) {
                log.info("Created {} S-type child commands for command: {}", childCount, command.getId());
                startMerge(cmdId, config, baseFileInfo);
                result.markChildrenCreated();
            } else {
                result.markChildrenFailed("No buckets or child command creation returned 0");
            }
        } else {
            // SERIAL 模式:异步切分,不阻塞 Handler 线程
            // 先标记结果状态为 P,让 orchestrator 写入 P 状态;
            // 切分完成后在回调中创建子命令 + 启动合并
            result.markChildrenCreated();
            splitFlowService.startSplitAsync(cmdId, config,
                    // onComplete: 切分完成 → 创建子命令 + 启动合并
                    () -> {
                        int cnt = bucketDistributor.createChildCommands(cmdId,
                                config.getCategoryCode(), config.getControlCode(), baseFilePath);
                        if (cnt > 0) {
                            log.info("Split+child creation done for {}, {} child commands", cmdId, cnt);
                            startMerge(cmdId, config, baseFileInfo);
                        } else {
                            log.warn("Split done but no buckets created for {}", cmdId);
                            commandRepository.updateStatus(cmdId, ColumnNames.STATUS_SKIPPED);
                        }
                    },
                    // onError: 切分失败 → 主命令置 E
                    e -> commandRepository.updateStatus(cmdId, ColumnNames.STATUS_ERROR));
        }
    }

    /**
     * 启动异步合并流程,onSuccess/onError 回调在此定义,指令表状态更新代码在 Handler 中可见,
     * 便于排查异步流程的终态走向。
     */
    private void startMerge(Long cmdId, TransferConfig config, ResolvedPath baseFileInfo) {
        mergeFlowService.startMergeAsync(cmdId, config, baseFileInfo,
                // onSuccess: 合并成功,主命令终态置 Y
                // 条件更新(P→Y):避免覆盖其他路径(如子命令失败)已写入的 E 状态
                () -> {
                    commandRepository.updateStatusIfProcessing(cmdId, ColumnNames.STATUS_SUCCESS);
                    log.info("Merge succeeded, main command {} status -> Y", cmdId);
                },
                // onError: 合并失败(如子节点报错),主命令终态置 E
                () -> {
                    commandRepository.updateStatus(cmdId, ColumnNames.STATUS_ERROR);
                    log.warn("Merge failed, main command {} status -> E", cmdId);
                }
        );
        log.info("Started merge flow for command: {}", cmdId);
    }
}