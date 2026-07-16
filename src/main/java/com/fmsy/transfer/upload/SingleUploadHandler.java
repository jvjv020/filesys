package com.fmsy.transfer.upload;

import com.fmsy.enums.CommandType;
import com.fmsy.exception.FlagCheckException;
import com.fmsy.model.Command;
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

import java.util.List;
import java.util.Map;

/**
 * 单文件上传 Handler — 处理 UPLOAD_SINGLE（SERIAL）和 UPLOAD_MULTI（BATCH）两种场景。
 *
 * <p>两种场景共享相同的上传管线：<br>
 * preCheck → truncateTable → insertDataAndVerify → postProcess
 *
 * <p>区别仅在于文件路径解析方式：
 * <ul>
 *   <li><b>SERIAL（UPLOAD_SINGLE）</b>：从配置路径直接解析</li>
 *   <li><b>BATCH（UPLOAD_MULTI + BATCH）</b>：从明细表取 {@code FILE_NAME} 替换路径中的
 *       {@code {FILE_NAME}} 占位符；明细的 {@code FIELD_NAME/FIELD_VALUE} 作为
 *       extraFields 传入 FieldMapping</li>
 * </ul>
 *
 * <p>前稽核与后审计已合并到 insertDataAndVerify 中，在落库后使用
 * CloseableIterator.getRecordCount() 统一校验，无需额外 FTP 流。
 * 清表操作在落库之前执行，确保数据完整性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleUploadHandler implements TransferHandler {

    private final DetailRepository detailRepository;
    private final UploadSupport support;
    private final TransferSupport transferSupport;
    private final FieldMappingBuilder fieldMappingBuilder;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        if (command.getCommandType() == CommandType.BATCH) {
            handleBatch(command, config, result);
        } else {
            handleSingle(command, config, result);
        }
    }

    /**
     * UPLOAD_SINGLE（SERIAL）模式：从配置路径直接解析文件并上传，
     * 走 preCheck → truncateTable → insertDataAndVerify → postProcess 管线。
     */
    private void handleSingle(Command command, TransferConfig config, Result result) throws Exception {
        ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), command);
        String filePath = fileInfo.fullPath();
        String ftpName = config.getFtpName();
        Integer auditCount = command.getAuditCount();

        try {
            // 在 FTP 连接上下文中执行 preCheck → insertDataAndVerify → postProcess
            // 前稽核与后审计已合并到 insertDataAndVerify 中，在落库后统一校验
            UploadSupport.UploadResult r = transferSupport.executeWithClient(ftpName, client -> {
                // Phase 1: preCheck — 标志文件检查
                UploadSupport.UploadResult checkResult = support.preCheck(client, config, fileInfo, filePath);
                if (checkResult != null) {
                    return checkResult; // SKIPPED
                }

                // Phase 2: 清表 — 落库之前
                if (BooleanUtils.isYes(config.getClearTableFlag())) {
                    support.truncateTable(config);
                }

                // Phase 3: insert + 前后审计（单事务）
                // 前稽核与后审计使用 CloseableIterator.getRecordCount() 统一校验
                int count = support.insertDataAndVerify(client, config, fileInfo, null, filePath, auditCount);

                // Phase 5: postProcess — FTP 后操作
                support.postProcess(client, config, fileInfo, count);

                return new UploadSupport.UploadResult(count, 1, 0, 0, null);
            });

            // 根据结果设置结果表状态
            if (ColumnNames.STATUS_SKIPPED.equals(r.status())) {
                result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed: flag not found");
                return;
            }
            // status=null 表示成功
            result.setOutcome(r.records(), ColumnNames.STATUS_SUCCESS, "");

        } catch (FlagCheckException e) {
            // FLAG 比对失败 → 迁文件到 error 目录
            support.moveDataAndFlagToErrorDir(ftpName, filePath, config);
            log.warn("FLAG check failed, data file: {}, detail: {}", filePath, e.getMessage());
            result.setOutcome(0, ColumnNames.STATUS_ERROR, "FLAG check failed: " + e.getMessage());

        } catch (RuntimeException e) {
            // 前稽核/后审计失败 → 迁文件到 error 目录
            support.moveDataAndFlagToErrorDir(ftpName, filePath, config);
            log.error("Upload failed for {}: {}", filePath, e.getMessage());
            result.setOutcome(0, ColumnNames.STATUS_ERROR, e.getMessage());
        }
    }

    /**
     * BATCH 模式：从明细表获取文件名并上传单个文件。
     *
     * <p>与 {@link #handleSingle} 走相同的 preCheck → truncateTable →
     * insertDataAndVerify → postProcess 管线，仅文件路径解析方式不同：
     * 从明细表取 {@code FILE_NAME} 替换路径中的 {@code {FILE_NAME}} 占位符。
     *
     * <p>明细表的 {@code FIELD_NAME/FIELD_VALUE} 通过 detailContext 参数
     * 传递给 insertDataAndVerify，内部自动构建包含 extraFields 的 FieldMapping。
     */
    private void handleBatch(Command command, TransferConfig config, Result result) throws Exception {
        String ftpName = config.getFtpName();
        String nodeId = config.getNodeId();

        // 1. 加载明细表，获取待处理文件
        List<Map<String, Object>> details = detailRepository.findUploadDetails(
                command.getId(), ColumnNames.STATUS_EMPTY);
        if (details.isEmpty()) {
            log.info("No details found for BATCH command: {}", command.getId());
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "No details found");
            return;
        }

        Map<String, Object> detail = details.get(0);
        Long detailId = ((Number) detail.get(ColumnNames.DETAIL_ID)).longValue();
        String fileName = (String) detail.get(ColumnNames.FILE_NAME);
        if (fileName == null || fileName.isEmpty()) {
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_SKIPPED, nodeId);
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Empty FILE_NAME in detail");
            return;
        }

        // 2. 构建路径上下文，替换 {FILE_NAME} 占位符
        String detailFieldName = (String) detail.get(ColumnNames.FIELD_NAME);
        String detailFieldValue = (String) detail.get(ColumnNames.FIELD_VALUE);
        Map<String, String> pathContext = transferSupport.buildContext(
                command, detailFieldName, detailFieldValue);
        pathContext.put("FILE_NAME", fileName);

        ResolvedPath fileInfo = transferSupport.resolveFilePath(config.getFilePath(), pathContext);
        String filePath = fileInfo.fullPath();

        // 3. 取明细稽核数（优先于 command 级）
        Integer auditCount = detail.get(ColumnNames.AUDIT_COUNT) != null
                ? ((Number) detail.get(ColumnNames.AUDIT_COUNT)).intValue() : null;

        try {
            UploadSupport.UploadResult r = transferSupport.executeWithClient(ftpName, client -> {
                // Phase 1: preCheck — 标志文件检查
                UploadSupport.UploadResult checkResult = support.preCheck(client, config, fileInfo, filePath);
                if (checkResult != null) {
                    detailRepository.updateStatus(detailId, ColumnNames.STATUS_SKIPPED, nodeId);
                    return checkResult; // SKIPPED
                }

                // Phase 2: 清表 — 落库之前
                if (BooleanUtils.isYes(config.getClearTableFlag())) {
                    support.truncateTable(config);
                }

                // Phase 3: insert + 前后审计（单事务）
                // 将明细行作为 detailContext 传入，内部自动构建带 extraFields 的 FieldMapping
                int count = support.insertDataAndVerify(
                        client, config, fileInfo, detail, filePath, auditCount);

                // Phase 4: postProcess — FTP 后操作
                support.postProcess(client, config, fileInfo, count);

                return new UploadSupport.UploadResult(count, 1, 0, 0, null);
            });

            // 根据结果更新明细状态和结果表
            if (ColumnNames.STATUS_SKIPPED.equals(r.status())) {
                // preCheck 失败时已在回调内更新明细状态为 SKIPPED
                result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Pre-check failed: flag not found");
                return;
            }

            // 成功：更新明细状态
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_SUCCESS, nodeId);
            result.setOutcome(r.records(), ColumnNames.STATUS_SUCCESS, "");

        } catch (FlagCheckException e) {
            // FLAG 比对失败 → 迁文件 + 更新明细状态
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_ERROR, nodeId);
            support.moveDataAndFlagToErrorDir(ftpName, filePath, config);
            log.warn("FLAG check failed, data file: {}, detail: {}", filePath, e.getMessage());
            result.setOutcome(0, ColumnNames.STATUS_ERROR, "FLAG check failed: " + e.getMessage());

        } catch (RuntimeException e) {
            // 其他异常 → 迁文件 + 更新明细状态
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_ERROR, nodeId);
            support.moveDataAndFlagToErrorDir(ftpName, filePath, config);
            log.error("Upload failed for {}: {}", filePath, e.getMessage());
            result.setOutcome(0, ColumnNames.STATUS_ERROR, e.getMessage());
        }
    }
}