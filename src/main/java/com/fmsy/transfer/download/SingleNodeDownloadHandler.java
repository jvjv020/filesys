package com.fmsy.transfer.download;

import com.fmsy.enums.CommandType;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.BucketDistributor;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.transfer.download.BucketProcessor.BucketBatchResult;
import com.fmsy.transfer.download.BucketProcessor.BucketProcessingOptions;
import static com.fmsy.transfer.TransferSupport.determineMainStatus;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.FilePathUtils;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * DOWNLOAD_SINGLE_NODE 场景:按拆分字段分桶,每个桶生成一个文件,并行下载到同一节点 FTP。
 *
 * <p>桶的并行处理委托 {@link BucketProcessor#processAll} 执行,
 * Handler 负责顶层 preCheck、分桶、总标志文件和聚合后稽核。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleNodeDownloadHandler implements TransferHandler {

    private final BucketProcessor bucketProcessor;
    private final BucketDistributor bucketDistributor;
    private final TransferSupport transferSupport;
    private final DownloadSupport downloadSupport;
    private final FieldMappingBuilder fieldMappingBuilder;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Single node multi-file download, command={}, table={}",
                command.getId(), config.getTableName());
        CommandType commandType = command.getCommandType();

        // ===== Phase 1: splitFields + 顶层 preCheck =====
        String splitFields = config.getSplitFields();
        if (splitFields == null || splitFields.isEmpty()) {
            log.warn("No split fields configured for DOWNLOAD_SINGLE_NODE");
            result.setOutcome(0, determineMainStatus(true, 0, 0), "");
            return;
        }
        String postOps = config.getPostOperations();

        ResolvedPath baseFileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);
        boolean preCheckOk = transferSupport.executeWithClient(config.getFtpName(), client -> {
            if (!transferSupport.preCheck(client, config, baseFileInfo)) {
                result.setOutcome(0, determineMainStatus(false, 0, 1), "Pre-check failed");
                return false;
            }
            String parentDir = FilePathUtils.extractParentDirectory(baseFileInfo.fullPath());
            if (parentDir != null && !parentDir.isEmpty()) {
                client.mkdirs(parentDir);
            }
            return true;
        });
        if (!preCheckOk) return;

        // ===== Phase 2: SERIAL 模式顶层 pre-audit =====
        if (commandType != CommandType.BATCH && command.getAuditCount() != null && command.getAuditCount() >= 0) {
            int auditResult = downloadSupport.preAudit(config, command.getAuditCount());
            if (auditResult < 0) {
                result.setOutcome(0, determineMainStatus(false, 0, 1), "Pre-audit failed");
                return;
            }
        }

        // ===== Phase 3: 分桶 =====
        List<Detail> buckets = new ArrayList<>();
        if (commandType == CommandType.BATCH) {
            buckets = bucketDistributor.getBuckets(command.getId(), Integer.MAX_VALUE);
            if (buckets.isEmpty()) {
                log.info("No buckets found for BATCH command: {}", command.getId());
                result.setOutcome(0, determineMainStatus(true, 0, 0), "");
                return;
            }
        } else {
            for (String fieldValue : bucketDistributor.distinctBuckets(config)) {
                Detail bucket = new Detail();
                bucket.setFieldValue(fieldValue);
                buckets.add(bucket);
            }
        }

        // ===== Phase 4: 并行桶处理(每桶通过 BucketProcessor 执行管线) =====
        // 预构建 FieldMapping 供所有桶共享（避免每桶重复查表元数据）
        FieldMapping sharedMapping = fieldMappingBuilder.buildForDownload(config);
        int concurrency = config.getConcurrency() != null ? config.getConcurrency() : 3;
        BucketBatchResult br = bucketProcessor.processAll(buckets, config, baseFileInfo,
                config.getFtpName(),
                BucketProcessingOptions.builder()
                        .pipelineCustomizer((pipelineOpts, bucket, fileInfo) -> {
                            // 复用预构建的字段映射，避免每桶重复查询表元数据
                            pipelineOpts.setFieldMapping(sharedMapping);
                            // BATCH 模式:用明细表的 auditCount
                            if (command.getCommandType() == CommandType.BATCH) {
                                Integer detailAudit = bucket.getAuditCount();
                                if (detailAudit != null && detailAudit >= 0) {
                                    pipelineOpts.setExpectedAuditCount(detailAudit);
                                }
                            }
                            pipelineOpts.setEnablePreCheck(false);   // 顶层已做
                            pipelineOpts.setEnablePostAudit(false);  // 聚合层做
                        })
                        .maxConcurrency(concurrency)
                        .build(),
                config.getNodeId());

        // ===== Phase 5: 总标志文件(仅在所有桶成功时生成) =====
        if (br.isAllSuccess() && postOps != null && postOps.contains("TOTAL")) {
            String totalFlagOps = FlagFileService.filterOpsByType(postOps, "TOTAL");
            if (totalFlagOps != null) {
                transferSupport.executeWithClient(config.getFtpName(), postClient -> {
                    transferSupport.postProcess(postClient, totalFlagOps, baseFileInfo, null);
                    return null;
                });
            }
        }

        // ===== Phase 6: 聚合后稽核 =====
        if (command.getAuditCount() != null && command.getAuditCount() >= 0) {
            boolean postAuditOk = downloadSupport.postAudit(config, baseFileInfo.fullPath());
            if (!postAuditOk) {
                log.error("Post-audit failed for command {}, rolling back {} generated file(s)",
                        command.getId(), br.getGeneratedFiles().size());
                transferSupport.executeWithClient(config.getFtpName(), client -> {
                    for (String f : br.getGeneratedFiles()) {
                        DownloadSupport.rollbackAfterPostAuditFailure(client, f,
                                "single-node download post-audit");
                    }
                    return null;
                });
                br.forceFailure();
            }
        }

        result.setOutcome(br.getTotalRecordCount(),
                br.determineStatus(), "");
    }
}
