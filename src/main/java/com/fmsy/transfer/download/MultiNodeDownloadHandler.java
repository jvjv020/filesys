package com.fmsy.transfer.download;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DOWNLOAD_MULTI_NODE 场景:主命令分桶后创建 S 型子命令,各节点竞争处理。
 *
 * <ul>
 *   <li>BATCH 模式:复用明细表已存在的桶,更新每个桶的 auditCount,再创建 S 子命令</li>
 *   <li>SERIAL 模式:从目标表 streamQuery DISTINCT 拿分桶值,创建桶 + 创建 S 子命令</li>
 *   <li>成功:result.markChildrenCreated(childCount)(主命令保持 PROCESSING,
 *       由 {@code AbstractTransferOrchestrator} finalize 之后统一启停 monitor)</li>
 *   <li>失败:result.markChildrenFailed(reason)(主命令置 ERROR,不启 monitor)</li>
 * </ul>
 *
 * <p>子命令的并发执行由 DetailPollingService 接管(本 Handler 不参与)。
 *
 * <p>preCheck 用短生命周期 FTP 连接,完成后立即归还;
 * createChildren 为纯 DB 操作时不持有 FTP 连接。
 * monitor 启停由 Orchestrator 统一调度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiNodeDownloadHandler implements TransferHandler {

    private final BucketDistributor bucketDistributor;
    private final DetailRepository detailRepository;
    private final TargetTableRepository targetTableRepository;
    private final TransferSupport transferSupport;
    private final FtpPool ftpPool;
    private final DataSourceConfig.DbPool dbPool;

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
        {
            FtpClient client = ftpPool.getClient(config.getFtpName());
            try {
                if (!transferSupport.preCheck(client, config, baseFileInfo)) {
                    result.markChildrenFailed("Pre-check failed");
                    return;
                }
            } finally {
                client.close();
            }
        }

        // Phase 2: DB-only work (no FTP client held)
        CommandType commandType = command.getCommandType();
        int childCount = commandType == CommandType.BATCH
                ? createChildrenForBatch(command, config, baseFilePath, splitFields)
                : createChildrenForSerial(command, config, baseFilePath, splitFields);
        if (childCount > 0) {
            log.info("Created {} S-type child commands for command: {}", childCount, command.getId());
            result.markChildrenCreated(childCount);
        } else {
            result.markChildrenFailed("No buckets or child command creation returned 0");
        }
    }

    /**
     * BATCH 模式:逐桶预统计 auditCount 后创建 S 型子命令。
     * <p>整段包在事务中:任一桶的 audit_count 写入或任一子命令创建失败 → 全部回滚,
     * 避免"已有桶审计数已更新但子命令半创建"的中间态。
     */
    private int createChildrenForBatch(Command command, TransferConfig config,
                                       String baseFilePath, String splitFields) {
        List<Detail> existingBuckets = bucketDistributor.getBuckets(command.getId(), Integer.MAX_VALUE);
        if (existingBuckets.isEmpty()) {
            log.info("No existing buckets found for BATCH command: {}", command.getId());
            return 0;
        }

        return dbPool.getTransactionTemplate(config.getDbName()).execute(status -> {
            for (Detail bucket : existingBuckets) {
                int recordCount = targetTableRepository.countByBucket(
                        config.getDbName(), config.getTableName(), splitFields, bucket.getFieldValue());
                detailRepository.updateAuditCount(bucket.getId(), recordCount);
                log.debug("Updated bucket {} with audit count {}", bucket.getId(), recordCount);
            }

            return bucketDistributor.createChildCommands(command.getId(),
                    config.getCategoryCode(), config.getControlCode(), baseFilePath);
        });
    }

    /**
     * SERIAL 模式:从目标表拉 distinct 分桶值 → 写桶 → 创建 S 型子命令。
     * <p>createBuckets + createChildCommands 包在事务中:避免"桶已写但子命令未创建"
     * 的孤儿状态(孤儿桶永久无主,只能人工清理)。
     */
    private int createChildrenForSerial(Command command, TransferConfig config,
                                        String baseFilePath, String splitFields) {
        List<String> bucketValues = bucketDistributor.distinctBuckets(config);
        if (bucketValues.isEmpty()) {
            log.info("No data found for DOWNLOAD_MULTI_NODE command: {}", command.getId());
            return 0;
        }

        return dbPool.getTransactionTemplate(config.getDbName()).execute(status -> {
            bucketDistributor.createBuckets(command.getId(), bucketValues, splitFields,
                    config.getCategoryCode(), config.getControlCode());
            return bucketDistributor.createChildCommands(command.getId(),
                    config.getCategoryCode(), config.getControlCode(), baseFilePath);
        });
    }
}
