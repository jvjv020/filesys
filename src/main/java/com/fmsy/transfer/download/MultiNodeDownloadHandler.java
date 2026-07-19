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
        int childCount;
        if (commandType == CommandType.BATCH) {
            // BATCH 模式:复用明细表已有桶,统计审计数后创建 S 子命令
            childCount = bucketDistributor.prepareBatchChildren(command, config, baseFilePath, splitFields);
        } else {
            // SERIAL 模式:SplitFlowService 按 PK 范围切分桶(写入 specName 供子节点使用),
            // 然后为所有空桶创建 S 型子命令
            splitFlowService.splitSync(command.getId(), config);
            childCount = bucketDistributor.createChildCommands(command.getId(),
                    config.getCategoryCode(), config.getControlCode(), baseFilePath);
        }

        if (childCount > 0) {
            log.info("Created {} S-type child commands for command: {}", childCount, command.getId());
            // Phase 3: 启动异步合并流程(轮询 Y 桶 → APPE 合并 → 写标志文件)
            // onSuccess/onError 回调在此定义,指令表状态更新代码在 Handler 中可见,
            // 便于排查异步流程的终态走向
            Long cmdId = command.getId();
            mergeFlowService.startMergeAsync(cmdId, config, baseFileInfo,
                    // onSuccess: 合并成功,主命令终态置 Y
                    () -> {
                        commandRepository.updateStatus(cmdId, ColumnNames.STATUS_SUCCESS);
                        log.info("Merge succeeded, main command {} status -> Y", cmdId);
                    },
                    // onError: 合并失败(如子节点报错),主命令终态置 E
                    () -> {
                        commandRepository.updateStatus(cmdId, ColumnNames.STATUS_ERROR);
                        log.warn("Merge failed, main command {} status -> E", cmdId);
                    }
            );
            log.info("Started merge flow for command: {}", command.getId());
            result.markChildrenCreated();
        } else {
            result.markChildrenFailed("No buckets or child command creation returned 0");
        }
    }
}