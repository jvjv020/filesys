package com.fmsy.transfer.download;

import com.fmsy.enums.CommandType;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
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
 *   <li>SERIAL 模式:从目标表 streamQuery DISTINCT 拿分桶值,创建桶 + 创建 S 子命令</li>
 *   <li>成功:result.markChildrenCreated()(主命令保持 PROCESSING,
 *       由 MergeFlowService 在合并完成后更新终态)</li>
 *   <li>失败:result.markChildrenFailed(reason)(主命令置 ERROR)</li>
 * </ul>
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
        int childCount = commandType == CommandType.BATCH
                ? bucketDistributor.prepareBatchChildren(command, config, baseFilePath, splitFields)
                : bucketDistributor.prepareSerialChildren(command, config, baseFilePath, splitFields);
        if (childCount > 0) {
            log.info("Created {} S-type child commands for command: {}", childCount, command.getId());
            result.markChildrenCreated(childCount);
        } else {
            result.markChildrenFailed("No buckets or child command creation returned 0");
        }
    }
}
