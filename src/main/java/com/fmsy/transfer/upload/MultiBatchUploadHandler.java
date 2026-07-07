package com.fmsy.transfer.upload;

import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.BooleanUtils;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UPLOAD_MULTI + BATCH 场景:按明细表逐文件上传(顺序执行,非并行)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiBatchUploadHandler implements TransferHandler {

    private final DetailRepository detailRepository;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final UploadSupport support;
    private final TransferSupport transferSupport;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Multi-file upload with details");
        String nodeId = config.getNodeId();

        transferSupport.executeWithClient(config.getFtpName(), client -> {
            if (!transferSupport.preCheck(client, config, null)) {
                result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed");
                return null;
            }

            List<Map<String, Object>> details = detailRepository.findUploadDetails(
                    command.getId(), ColumnNames.STATUS_EMPTY);

            if (details.isEmpty()) {
                log.info("No details found for command: {}", command.getId());
                result.setOutcome(0, UploadSupport.determineMainStatus(UploadSupport.UploadResult.allSkipped()), "");
                return null;
            }

            if (BooleanUtils.isYes(config.getClearTableFlag())) {
                support.truncateTable(config);
            }

            int totalRecords = 0;
            int successCount = 0;
            int skippedCount = 0;
            int failedCount = 0;
            ResolvedPath lastFileInfo = null;
            for (Map<String, Object> detail : details) {
                UploadSupport.UploadResult perFile = processOneDetail(command, config, client, detail, nodeId);
                totalRecords += perFile.records();
                successCount += perFile.successCount();
                skippedCount += perFile.skippedCount();
                failedCount += perFile.failedCount();

                // 记录最后一个成功处理文件的 ResolvedPath，供后置操作的文件衍生变量使用
                String fileName = (String) detail.get(ColumnNames.FILE_NAME);
                if (fileName != null && !fileName.isEmpty() && perFile.successCount() > 0) {
                    lastFileInfo = resolveDetailFilePath(command, config, detail, fileName);
                }
            }

            // 后置处理（使用最后一个成功文件的 ResolvedPath）
            Map<String, String> extra = new HashMap<>();
            extra.put("C", String.valueOf(totalRecords));
            transferSupport.postProcess(client, config, lastFileInfo, extra);

            UploadSupport.UploadResult ur = new UploadSupport.UploadResult(totalRecords, successCount, skippedCount, failedCount);
            result.setOutcome(ur.records(), UploadSupport.determineMainStatus(ur), "");
            return null;
        });
    }

    private UploadSupport.UploadResult processOneDetail(Command command, TransferConfig config,
                                                        FtpClient client, Map<String, Object> detail,
                                                        String nodeId) {
        Long detailId = ((Number) detail.get(ColumnNames.DETAIL_ID)).longValue();
        String fileName = (String) detail.get(ColumnNames.FILE_NAME);

        if (fileName == null || fileName.isEmpty()) {
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_SKIPPED, nodeId);
            return new UploadSupport.UploadResult(0, 0, 1, 0);
        }

        ResolvedPath fileInfo = resolveDetailFilePath(command, config, detail, fileName);

        Integer detailAuditCount = detail.get(ColumnNames.AUDIT_COUNT) != null
                ? ((Number) detail.get(ColumnNames.AUDIT_COUNT)).intValue() : -1;
        FileConverter converter = ConverterFactory.get(config.getParserType());
        int fileLineCount = support.preAudit(detailAuditCount, config, fileInfo.fullPath(), converter);
        if (fileLineCount < 0) {
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_ERROR, nodeId);
            return new UploadSupport.UploadResult(0, 0, 0, 1);
        }

        try (InputStream is = client.getInputStream(fileInfo.fullPath())) {
            FieldMapping mapping = fieldMappingBuilder.buildForUpload(config, detail);

            try (CloseableIterator<List<Map<String, Object>>> dataIter =
                    new CloseableIterator<>(converter.parse(is, mapping))) {
                int count = support.insertBatchInTx(config, dataIter, mapping);
                int actualFileRecords = dataIter.getRecordCount();
                boolean postAuditOk = support.postAudit(config, actualFileRecords, count);
                if (postAuditOk) {
                    detailRepository.updateStatus(detailId, ColumnNames.STATUS_SUCCESS, nodeId);
                    log.info("Uploaded file from detail: {}", fileInfo.fullPath());
                    return new UploadSupport.UploadResult(count, 1, 0, 0);
                } else {
                    detailRepository.updateStatus(detailId, ColumnNames.STATUS_ERROR, nodeId);
                    return new UploadSupport.UploadResult(0, 0, 0, 1);
                }
            }
        } catch (Exception e) {
            log.error("Failed to upload file from detail: {}", fileInfo.fullPath(), e);
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_ERROR, nodeId);
            return new UploadSupport.UploadResult(0, 0, 0, 1);
        }
    }

    /**
     * 解析明细行对应的文件路径:
     * 1) 用 EXTRA_INFO + detail.fieldName/fieldValue 拆分填充占位符上下文
     * 2) 解析目录模板 + "/" + fileName
     */
    private ResolvedPath resolveDetailFilePath(Command command, TransferConfig config,
                                                Map<String, Object> detail, String fileName) {
        String detailFieldName = (String) detail.get(ColumnNames.FIELD_NAME);
        String detailFieldValue = (String) detail.get(ColumnNames.FIELD_VALUE);
        Map<String, String> detailContext = transferSupport.buildContext(
                command, detailFieldName, detailFieldValue);
        return transferSupport.resolveFilePath(
                config.getFilePath() + "/" + fileName, detailContext);
    }
}
