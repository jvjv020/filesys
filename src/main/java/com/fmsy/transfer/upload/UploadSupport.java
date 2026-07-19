package com.fmsy.transfer.upload;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.fileops.FlagFileService;
import com.fmsy.ftp.FtpClient;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import com.fmsy.util.SystemConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 上传场景 Support — 跨所有 Upload Handler 共享的上传协议纯方法集合。
 *
 * <p>
 * 每个方法只做一件事，Handler 负责组合编排各阶段：<br>
 * preCheck → truncateTable → insertDataAndVerify → postProcess
 *
 * <p>
 * 纯方法列表：
 * <ul>
 * <li>{@link #preCheck} — 前置标志文件检查</li>
 * <li>{@link #truncateTable} — 清空目标表</li>
 * <li>{@link #insertDataAndVerify} — 流式解析 + 批量插入 + 前后审计</li>
 * <li>{@link #postProcess} — FTP 后操作</li>
 * <li>{@link #moveDataAndFlagToErrorDir} — 异常文件迁移到 error 目录</li>
 * </ul>
 *
 * <p>
 * 前稽核与后审计已合并到 {@link #insertAndVerifyPerFileInTx} 中，
 * 在落库后使用 {@link CloseableIterator#getRecordCount()} 统一校验，
 * 无需额外打开 FTP 文件流做独立前稽核。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadSupport {

    private final TargetTableRepository targetTableRepository;
    private final DataSourceConfig.DbPool dbPool;
    private final TransferSupport transferSupport;
    private final ConverterFactory converterFactory;
    private final FieldMappingBuilder fieldMappingBuilder;

    // ==================== 纯阶段方法(每个方法只做一件事) ====================

    /**
     * 前置标志文件检查。
     *
     * <p>
     * 委托给 {@link TransferSupport#preCheck}，处理三种结果：
     * <ul>
     * <li>返回 null — 检查通过，可以继续后续处理</li>
     * <li>返回 {@link UploadResult}(SKIPPED) — 标志文件不存在，调用方应跳过该文件</li>
     * <li>抛出 {@link FlagCheckException} — FLAG 比对失败，调用方应迁文件到 error 目录</li>
     * </ul>
     *
     * @param client   已借出的 FTP 客户端
     * @param config   传输配置
     * @param fileInfo 数据文件路径信息
     * @param filePath 数据文件完整路径（仅用于日志）
     * @return null=通过，非null=跳过结果
     */
    public UploadResult preCheck(FtpClient client, TransferConfig config,
            ResolvedPath fileInfo, String filePath) {
        if (!transferSupport.preCheck(client, config, fileInfo)) {
            String flagPath = resolveConfiguredFlagPath(config.getPreOperations(), fileInfo);
            log.warn("Pre-check failed (flag not found), flag file: {}, data file: {}",
                    flagPath != null ? flagPath : "unknown", filePath);
            return new UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED);
        }
        return null;
    }

    /**
     * 清空目标表 — 在事务中执行 DELETE FROM。
     *
     * <p>
     * 由 Handler 在落库前调用，确保插入数据时目标表是空的。
     *
     * @param config 传输配置
     */
    public void truncateTable(TransferConfig config) {
        dbPool.getTransactionTemplate(config.getDbName()).execute(status -> {
            targetTableRepository.truncate(config.getDbName(), config.getTableName());
            return null;
        });
    }

    /**
     * 流式解析文件 → 批量插入 → 前后审计 — 单事务（使用预构建的 FieldMapping）。
     *
     * <p>
     * 多文件场景（SERIAL）下，各文件共享同一个 config 和 null detailContext，
     * 调用方可提前构建一次 FieldMapping 传入，避免重复查表元数据。
     * </p>
     *
     * <p>
     * 前稽核与后审计已合并到 {@link #insertAndVerifyPerFileInTx} 中，
     * 在落库后使用 {@link CloseableIterator#getRecordCount()} 统一校验，
     * 无需额外打开 FTP 文件流做独立前稽核。
     * </p>
     *
     * @param client     已借出的 FTP 客户端
     * @param config     传输配置
     * @param mapping    预构建的字段映射（可为 null，为 null 时自动构建）
     * @param filePath   FTP 文件路径
     * @param auditCount 稽核数（可为 null，非 null 时在落库后校验）
     * @return 成功插入的记录数
     */
    public int insertDataAndVerify(FtpClient client, TransferConfig config,
            FieldMapping mapping, String filePath, Integer auditCount) {
        FieldMapping effectiveMapping = mapping != null ? mapping
                : fieldMappingBuilder.buildForUpload(config, null);
        FileConverter converter = converterFactory.get(config.getParserType());

        // 前稽核已合并到 insertAndVerifyPerFileInTx 中，在落库后与后审计同时进行
        // 使用 CloseableIterator.getRecordCount() 统一获取文件实际记录数，无需额外 FTP 流
        try (InputStream is = client.getInputStream(filePath);
                CloseableIterator<List<Map<String, Object>>> dataIter = new CloseableIterator<>(
                        converter.parse(is, effectiveMapping))) {
            return insertAndVerifyPerFileInTx(config, dataIter, effectiveMapping, auditCount);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Insert failed for " + filePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * 流式解析文件 → 批量插入 → 后审计 — 单事务（自动构建 FieldMapping）。
     *
     * <p>
     * 自动根据 config 和 detailContext 构建 FieldMapping 后委托给
     * {@link #insertDataAndVerify(FtpClient, TransferConfig, FieldMapping, String, Integer)}。
     *
     * @param client        已借出的 FTP 客户端
     * @param config        传输配置
     * @param fileInfo      数据文件路径信息（仅用于日志，已弃用）
     * @param detailContext 明细上下文（可为 null，供 {FIELD_NAME} 占位符解析用）
     * @param filePath      FTP 文件路径
     * @param auditCount    稽核数（可为 null，用于落库后重新校验前稽核跳过的格式）
     * @return 成功插入的记录数
     */
    public int insertDataAndVerify(FtpClient client, TransferConfig config,
            ResolvedPath fileInfo, Map<String, Object> detailContext,
            String filePath, Integer auditCount) {
        FieldMapping mapping = fieldMappingBuilder.buildForUpload(config, detailContext);
        return insertDataAndVerify(client, config, mapping, filePath, auditCount);
    }

    /**
     * FTP 后操作 — 写入 SUB/FB/TOTAL 标志文件、删除文件、重命名等。
     *
     * <p>
     * 失败时仅记录日志，不影响主状态（数据已落库）。
     *
     * @param client      已借出的 FTP 客户端
     * @param config      传输配置
     * @param fileInfo    数据文件路径信息（用于路径继承和内容变量解析）
     * @param recordCount 成功插入的记录数（作为 {C} 变量传入后操作）
     */
    public void postProcess(FtpClient client, TransferConfig config,
            ResolvedPath fileInfo, int recordCount) {
        try {
            transferSupport.postProcess(client, config, fileInfo, Map.of("C", String.valueOf(recordCount)));
        } catch (Exception e) {
            log.warn("Post-process failed for {}: {}", fileInfo != null ? fileInfo.fullPath() : "unknown",
                    e.getMessage());
        }
    }

    // ==================== 单文件上传管线（跨 Handler 复用） ====================

    /**
     * 单文件上传管线 — preCheck → insertDataAndVerify → postProcess。
     *
     * <p>不包含 truncateTable（由调用方在适当时机执行）。
     * mapping 和 detailContext 通过 {@link UploadOptions} 传入，二选一：
     * <ul>
     *   <li>prebuiltMapping != null — 使用预构建的 FieldMapping（多文件并行场景）</li>
     *   <li>detailContext != null — 使用明细行上下文构建 FieldMapping（BATCH 场景）</li>
     *   <li>两者都 null — 自动构建 FieldMapping（SERIAL 单文件场景）</li>
     * </ul>
     *
     * @param client   已借出的 FTP 客户端
     * @param config   传输配置
     * @param fileInfo 数据文件路径信息
     * @param filePath 数据文件完整路径
     * @param opts     上传选项（mapping/detailContext/auditCount）
     * @return 上传结果
     */
    public UploadResult executeFilePipeline(FtpClient client, TransferConfig config,
            ResolvedPath fileInfo, String filePath, UploadOptions opts) {

        // Phase 1: preCheck
        UploadResult checkResult = preCheck(client, config, fileInfo, filePath);
        if (checkResult != null) return checkResult;

        // Phase 2: insert + verify
        int count;
        if (opts.prebuiltMapping() != null) {
            count = insertDataAndVerify(client, config, opts.prebuiltMapping(), filePath, opts.auditCount());
        } else {
            count = insertDataAndVerify(client, config, fileInfo, opts.detailContext(), filePath, opts.auditCount());
        }

        // Phase 3: postProcess
        postProcess(client, config, fileInfo, count);

        return new UploadResult(count, 1, 0, 0, null);
    }

    /**
     * 安全版上传管线 — 自动管理 FTP 连接生命周期 + 异常时迁移文件到 error 目录。
     *
     * <p>
     * 返回 UploadResult 而非抛出异常，调用方根据 status 判断：
     * <ul>
     *   <li>status=null — 成功</li>
     *   <li>status=SKIPPED — preCheck 未通过（标志文件不存在）</li>
     *   <li>status=ERROR — 异常，文件已迁移到 error 目录</li>
     * </ul>
     *
     * @param ftpName  FTP 连接名
     * @param filePath 数据文件完整路径
     * @param fileInfo 数据文件路径信息
     * @param config   传输配置
     * @param opts     上传选项（mapping/detailContext/auditCount）
     * @return 上传结果
     */
    public UploadResult safeExecuteFilePipeline(String ftpName, String filePath,
            ResolvedPath fileInfo, TransferConfig config, UploadOptions opts) {
        try {
            return transferSupport.executeWithClient(ftpName, client ->
                    executeFilePipeline(client, config, fileInfo, filePath, opts));
        } catch (Exception e) {
            log.error("safeExecuteFilePipeline error for {}: {}", filePath, e.getMessage(), e);
            moveDataAndFlagToErrorDir(ftpName, filePath, config);
            return new UploadResult(0, 0, 0, 1, ColumnNames.STATUS_ERROR);
        }
    }

    // ==================== 后审计 ====================

    /**
     * 后审计：本次插入记录数 vs 文件记录数（Upload 语义）。
     *
     * <p>
     * 比对的是"文件记录数"与"成功插入的记录数"，而非全表 COUNT。
     * 多文件场景下，全表 COUNT 包含其他文件的数据，对比无意义。
     *
     * @param config        传输配置
     * @param fileLineCount 该文件的记录行数（由 CloseableIterator 迭代统计）
     * @param insertedCount 本次成功插入的记录数
     * @return true=审计通过
     */
    public boolean postAudit(TransferConfig config, int fileLineCount, int insertedCount) {
        boolean passed = fileLineCount == insertedCount;
        log.info("Post-audit upload: fileRecords={}, inserted={}, passed={}",
                fileLineCount, insertedCount, passed);
        return passed;
    }

    // ==================== 核心流式插入（单事务） ====================

    /**
     * 在事务中执行数据插入 — 批量插入循环。
     *
     * <p>
     * 空数据处理（handleEmptyData）在事务内完成，失败时全部回滚。
     *
     * @return 成功插入的记录数
     */
    private int executeInsertInTx(TransferConfig config, Iterator<List<Map<String, Object>>> dataIter,
            FieldMapping mapping, List<String> fields) {
        int batchSize = SystemConstants.DEFAULT_BATCH_SIZE;
        List<Object[]> batch = new ArrayList<>();
        int totalRecords = 0;

        while (dataIter.hasNext()) {
            List<Map<String, Object>> batchChunk = dataIter.next();
            if (batchChunk.isEmpty())
                continue;

            for (Map<String, Object> record : batchChunk) {
                Object[] values = new Object[fields.size()];
                for (int i = 0; i < fields.size(); i++) {
                    values[i] = mapping.getValue(record, fields.get(i));
                }
                batch.add(values);

                if (batch.size() >= batchSize) {
                    targetTableRepository.batchInsert(
                            config.getDbName(), config.getTableName(), fields, batch);
                    totalRecords += batch.size();
                    batch.clear();
                }
            }
        }

        if (!batch.isEmpty()) {
            targetTableRepository.batchInsert(
                    config.getDbName(), config.getTableName(), fields, batch);
            totalRecords += batch.size();
        }

        // 空数据判断在事务内，ERROR/SKIP 模式直接回滚
        if (!transferSupport.handleEmptyData(totalRecords, config.getEmptyDataHandling())) {
            throw new RuntimeException("Empty data handling: " + config.getEmptyDataHandling());
        }

        return totalRecords;
    }

    /**
     * 在事务中执行插入 + 前后审计 — 适用于多文件场景的每个文件。
     *
     * <p>
     * 前稽核与后审计在此处同时进行，均使用 {@link CloseableIterator#getRecordCount()}
     * 获取的文件实际记录数，无需额外打开 FTP 流做独立前稽核。
     * 后审计失败时始终回滚事务（数据完整性优先）。
     * 清表操作已提取到调用方，由各 Handler 在开始落库前统一调用。
     * </p>
     *
     * @param config     传输配置
     * @param dataIter   数据迭代器（需支持 {@link CloseableIterator#getRecordCount()}）
     * @param mapping    字段映射
     * @param auditCount 稽核数（可为 null；非 null 时在落库后校验）
     * @return 成功插入的记录数
     * @throws RuntimeException 审计失败时抛出，触发事务回滚
     */
    public int insertAndVerifyPerFileInTx(TransferConfig config,
            CloseableIterator<List<Map<String, Object>>> dataIter,
            FieldMapping mapping,
            Integer auditCount) {
        String dbName = config.getDbName();
        TransactionTemplate tx = dbPool.getTransactionTemplate(dbName);
        List<String> fields = mapping.getTableFields();

        return tx.execute(status -> {
            int totalRecords = executeInsertInTx(config, dataIter, mapping, fields);

            // 前稽核：与后审计同时进行，使用同一个 dataIter.getRecordCount()，无需额外 FTP 流
            int fileRecordCount = dataIter.getRecordCount();
            if (auditCount != null && auditCount >= 0 && fileRecordCount != auditCount) {
                throw new RuntimeException("Pre-audit failed: expected " + auditCount
                        + " records, got " + fileRecordCount + " for " + config.getTableName());
            }
            if (auditCount != null && auditCount >= 0) {
                log.debug("Pre-audit passed: {} records match expected {}", fileRecordCount, auditCount);
            }
            // 后审计：插入记录数 vs 文件记录数，不匹配则回滚（数据完整性优先）
            if (!postAudit(config, fileRecordCount, totalRecords)) {
                throw new RuntimeException("Post-audit failed: file records=" + fileRecordCount
                        + ", inserted=" + totalRecords + " for " + config.getTableName());
            }
            log.debug("Inserted {} records in single transaction (with verify)", totalRecords);
            return totalRecords;
        });
    }

    // ==================== 异常文件迁移 ====================

    /**
     * 将数据文件及配置的标志文件一起迁到 error 目录（自动管理 FTP 连接生命周期）。
     *
     * <p>
     * 委托给 {@link #moveDataAndFlagToErrorDir(FtpClient, String, TransferConfig)}，
     * 自动借还 FTP 连接。Handler 不再需要各自实现此逻辑。
     * </p>
     *
     * @param ftpName  FTP 连接名
     * @param filePath 数据文件完整路径
     * @param config   传输配置
     */
    public void moveDataAndFlagToErrorDir(String ftpName, String filePath, TransferConfig config) {
        try {
            transferSupport.executeWithClient(ftpName, client -> {
                moveDataAndFlagToErrorDir(client, filePath, config);
                return null;
            });
        } catch (Exception ex) {
            log.error("Failed to move files to error dir for {}: {}", filePath, ex.getMessage());
        }
    }

    /**
     * 将数据文件及配置的标志文件一起迁到 error 目录。
     *
     * <p>
     * 数据文件迁移委托给 {@link FtpClient#moveToErrorDir}（含时间戳重命名及通用标志文件后缀的迁移）。
     * 同时根据 preOperations 中的 FLAG/READY 路径，将配置的标志文件也迁到 error 目录。
     */
    public void moveDataAndFlagToErrorDir(FtpClient client, String filePath, TransferConfig config) {
        // 1. 迁数据文件（FtpClient 本身已处理 .OK/.ready/.flag 等通用后缀）
        try {
            client.moveToErrorDir(filePath);
        } catch (Exception e) {
            log.error("Failed to move error file {}: {}", filePath, e.getMessage());
        }
        // 2. 迁配置的标志文件（从 preOperations 中解析 FLAG/READY 路径）
        String flagPath = null;
        try {
            flagPath = resolveConfiguredFlagPath(config.getPreOperations(), ResolvedPath.of(filePath));
            if (flagPath != null && !flagPath.isEmpty() && !flagPath.equals(filePath)) {
                if (client.exists(flagPath)) {
                    client.moveToErrorDir(flagPath);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to move configured flag file {} for data file {}: {}",
                    flagPath != null ? flagPath : "unknown", filePath, e.getMessage());
        }
    }

    // ==================== 标志文件路径解析工具 ====================

    /**
     * 从前置操作字符串中提取第一个 FLAG/READY 路径模式。
     * 委托给 {@link FlagFileService#extractFlagPathPattern}。
     */
    public static String extractFlagPathPattern(String preOps) {
        return FlagFileService.extractFlagPathPattern(preOps);
    }

    /**
     * 从传输配置的前置操作中提取标志文件的完整路径。
     *
     * <p>
     * 解析 preOperations 字符串，找到第一个 FLAG 或 READY 操作，提取路径模式
     * 并解析文件衍生变量（{stem}, {name}, {ext}, {dir} 等）。
     *
     * @param preOps   前置操作字符串，如 "FLAG:{stem}.OK" 或 "READY:{stem}.ready"
     * @param fileInfo 数据文件的路径信息，用于解析文件衍生变量
     * @return 解析后的标志文件完整路径；没有 FLAG/READY 操作时返回 null
     */
    public static String resolveConfiguredFlagPath(String preOps, ResolvedPath fileInfo) {
        if (fileInfo == null)
            return null;
        String pathPattern = FlagFileService.extractFlagPathPattern(preOps);
        if (pathPattern == null)
            return null;

        // 展开文件衍生变量
        String resolved = TransferSupport.expandPathVariables(pathPattern, fileInfo);

        // 相对路径 → 继承数据文件的目录
        if (!resolved.startsWith("/") && fileInfo.dir() != null && !fileInfo.dir().isEmpty()) {
            resolved = fileInfo.dir() + "/" + resolved;
        }

        // 规范化 .. 段
        return FlagFileService.normalizePath(resolved);
    }

    /**
     * 规范化路径中的 ".." 段 — 委托给 {@link FlagFileService#normalizePath}。
     *
     * @deprecated 直接使用 {@link FlagFileService#normalizePath}
     */
    @Deprecated
    public static String normalizePathSlashes(String path) {
        return FlagFileService.normalizePath(path);
    }

    // ==================== 管线选项 / 结果 ====================

    /**
     * 上传管线选项 — 封装 {@link #executeFilePipeline} 和 {@link #safeExecuteFilePipeline} 的可选参数。
     *
     * <p>与下载侧的 {@code PipelineOptions} 对应，控制 mapping、detailContext 和 auditCount。
     * 三个字段可任意组合：
     * <ul>
     *   <li>prebuiltMapping != null — 使用预构建的 FieldMapping（多文件并行场景）</li>
     *   <li>detailContext != null — 使用明细行上下文构建 FieldMapping（BATCH 场景）</li>
     *   <li>两者都 null — 自动构建</li>
     *   <li>auditCount — 可为 null（跳过前稽核）</li>
     * </ul>
     *
     * @param prebuiltMapping 预构建的字段映射（可为 null）
     * @param detailContext   明细行上下文（可为 null，用于构建 extraFields）
     * @param auditCount      期望稽核数（可为 null，null=跳过稽核）
     */
    public record UploadOptions(FieldMapping prebuiltMapping, Map<String, Object> detailContext,
            Integer auditCount) {
        public static UploadOptions of(Integer auditCount) {
            return new UploadOptions(null, null, auditCount);
        }
    }

    /**
     * 单文件上传结果。
     *
     * @param records      成功插入的记录数（跳过或失败时为0）
     * @param successCount 成功文件数
     * @param skippedCount 跳过文件数
     * @param failedCount  失败文件数
     * @param status       null=成功，SKIPPED=跳过，ERROR=失败
     */
    public record UploadResult(int records, int successCount, int skippedCount, int failedCount,
            String status) {
        public static UploadResult allSkipped() {
            return new UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED);
        }
    }

}