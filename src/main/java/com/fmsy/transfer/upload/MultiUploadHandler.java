package com.fmsy.transfer.upload;

import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.exception.FlagCheckException;
import com.fmsy.exception.TransferException;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.DetailRepository;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferHandler;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.transfer.TransferUtils;
import com.fmsy.util.BooleanUtils;
import com.fmsy.util.ColumnNames;
import com.fmsy.util.ResolvedPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * UPLOAD_MULTI 场景：按指令类型分流。
 *
 * <ul>
 *   <li>{@link CommandType#SERIAL}（null）— 通配符匹配目录所有文件，单次 FTP 连接内预扫描后并行上传到同一张表</li>
 *   <li>{@link CommandType#BATCH}（'R'）— 按明细表逐文件上传（顺序执行）</li>
 * </ul>
 *
 * <p>配置路径中的 {@code {FILE_NAME}} 占位符按指令类型有不同的行为：</p>
 * <ul>
 *   <li><b>SERIAL</b>：{@code {FILE_NAME}} → {@code *}（通配符），用于 FTP 文件列表匹配</li>
 *   <li><b>BATCH</b>：{@code {FILE_NAME}} → 明细表的 {@code fileName} 值，得到具体文件路径</li>
 * </ul>
 * <p>示例：{@code /data/input/{YYYYMMDD}/BR{FILE_NAME}.csv}</p>
 *
 * <p>SERIAL 模式阶段顺序：<br>
 * 预扫描（单次 FTP 连接内完成列表+标志检查+孤立标志文件迁 error）→ 清表 → 落库+前后审计（per-file）→ 后操作
 *
 * <p>前稽核已合并到 insertDataAndVerify 的落库后阶段，与后审计使用
 * CloseableIterator.getRecordCount() 统一校验，无需额外 FTP 流做独立前稽核。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiUploadHandler implements TransferHandler {

    static final int TASK_SKIP = -1;
    static final int TASK_FAIL = -2;

    private final DetailRepository detailRepository;
    private final UploadSupport support;
    private final TransferSupport transferSupport;
    private final ConverterFactory converterFactory;
    private final FieldMappingBuilder fieldMappingBuilder;
    private final IntFunction<ExecutorService> batchExecutorFactory;

    @Override
    public void handle(Command command, TransferConfig config, Result result) throws Exception {
        if (command.getCommandType() == CommandType.BATCH) {
            handleBatch(command, config, result);
        } else {
            handleSerial(command, config, result);
        }
    }

    // ==================== SERIAL 模式：目录通配符 + 预扫描 + 并发 ====================

    /**
     * SERIAL 模式完整流程：
     * <ol>
     *   <li>解析配置路径，{FILE_NAME} → *，在单次 FTP 连接内完成列表 + 标志检查 + 异常文件处理</li>
     *   <li>统一清表（clearTableFlag=Y 时）</li>
     *   <li>对有效文件并行执行落库（insertDataAndVerify + postProcess），
     *       前稽核与后审计在落库后统一校验</li>
     *   <li>汇总后操作（postProcess）</li>
     * </ol>
     *
     * <p>路径配置示例：{@code /data/input/{YYYYMMDD}/BR{FILE_NAME}.csv}
     * — SERIAL 模式将 {FILE_NAME} 替换为 {@code *}，匹配所有 BR 开头的 csv 文件。</p>
     */
    private void handleSerial(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Multi-file upload from directory, command={}, table={}",
                command.getId(), config.getTableName());
        String ftpName = config.getFtpName();

        // 解析路径中的 {YYYYMMDD} 等占位符，保留 ResolvedPath 供后操作回退
        ResolvedPath resolvedDir = transferSupport.resolveFilePath(config.getFilePath(), command);
        String resolvedPath = resolvedDir != null ? resolvedDir.fullPath() : null;
        // {FILE_NAME} → *（通配符匹配），用于 FTP 文件列表
        String listPattern = resolvedPath != null ? resolvedPath.replace("{FILE_NAME}", "*") : null;

        /* ---------- Phase 1: 单次 FTP 连接内完成列表 + 标志检查 + 异常文件处理 ---------- */
        List<String> validFiles = prescanAndValidate(ftpName, listPattern, config);
        if (validFiles == null) {
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "Failed to list directory");
            return;
        }
        if (validFiles.isEmpty()) {
            log.info("No valid data files after prescan (pattern: {})", listPattern);
            result.setOutcome(0, TransferSupport.determineMainStatus(false, 0, 1), "");
            return;
        }
        log.info("Prescan: {} valid data files (pattern: {})", validFiles.size(), listPattern);

        /* ---------- Phase 2: 清表 ---------- */
        if (BooleanUtils.isYes(config.getClearTableFlag())) {
            support.truncateTable(config);
        }

        /* ---------- Phase 3: 并行落库 ---------- */
        int concurrency = config.getConcurrency() != null ? config.getConcurrency() : 3;
        int failedCount = 0;
        FieldMapping sharedMapping = fieldMappingBuilder.buildForUpload(config, null);

        ExecutorService insertExecutor = batchExecutorFactory.apply(concurrency);
        List<Future<Integer>> insertFutures = new ArrayList<>();
        for (String filePath : validFiles) {
            insertFutures.add(insertExecutor.submit(
                    () -> insertSingleFile(ftpName, filePath, ResolvedPath.of(filePath),
                            config, sharedMapping, null)));
        }
        shutdownExecutor(insertExecutor);

        // 收集落库结果
        int totalRecords = 0;
        ResolvedPath lastFileInfo = null;
        for (int i = 0; i < insertFutures.size(); i++) {
            try {
                Integer recordCount = insertFutures.get(i).get(30, TimeUnit.MINUTES);
                if (recordCount != null && recordCount > 0) {
                    totalRecords += recordCount;
                    lastFileInfo = ResolvedPath.of(validFiles.get(i));
                } else {
                    failedCount++;
                }
            } catch (Exception e) {
                log.error("Insert failed for file: {}", validFiles.get(i), e);
                failedCount++;
            }
        }

        // 落库失败处理
        if (failedCount > 0 && config.getEmptyDataHandling() == EmptyDataHandling.ERROR) {
            rollbackOnFailure(config, failedCount);
        }

        /* ---------- Phase 4: 汇总后操作 ---------- */
        ResolvedPath finalLastFileInfo = lastFileInfo != null ? lastFileInfo : resolvedDir;
        int finalTotalRecords = totalRecords;
        try {
            transferSupport.executeWithClient(ftpName, client -> {
                transferSupport.postProcess(client, config, finalLastFileInfo,
                        Map.of("C", String.valueOf(finalTotalRecords)));
                return null;
            });
        } catch (Exception e) {
            log.error("Phase 4 (postProcess) failed for serial upload: {}", e.getMessage(), e);
            result.setOutcome(totalRecords, ColumnNames.STATUS_ERROR,
                    "Data uploaded successfully (" + totalRecords + " records) but post-processing failed: "
                            + e.getMessage());
            return;
        }

        result.setOutcome(totalRecords,
                TransferSupport.determineMainStatus(failedCount == 0, failedCount, 0), "");
    }

    // ==================== BATCH 模式：明细表 + 顺序 ====================

    /**
     * BATCH 模式完整流程：
     * <ol>
     *   <li>加载明细表，获取待处理文件列表</li>
     *   <li>对每个明细文件执行前操作（preCheck），收集通过/跳过/失败结果</li>
     *   <li>统一清表（clearTableFlag=Y 时）</li>
     *   <li>对通过的文件顺序执行落库（insertDataAndVerify + postProcess），
     *       前稽核与后审计在落库后统一校验</li>
     *   <li>汇总后操作（postProcess）</li>
     * </ol>
     *
     * <p>路径配置示例：{@code /data/input/{YYYYMMDD}/BR{FILE_NAME}.csv}
     * — BATCH 模式将 {FILE_NAME} 替换为明细表的 {@code fileName} 值，得到具体文件路径。</p>
     */
    private void handleBatch(Command command, TransferConfig config, Result result) throws Exception {
        log.info("Multi-file upload with details, command={}, table={}",
                command.getId(), config.getTableName());
        String nodeId = config.getNodeId();
        String ftpName = config.getFtpName();

        List<Map<String, Object>> details = detailRepository.findUploadDetails(
                command.getId(), ColumnNames.STATUS_EMPTY);

        if (details.isEmpty()) {
            log.info("No details found for command: {}", command.getId());
            result.setOutcome(0, TransferSupport.determineMainStatus(false, 0, 1), "");
            return;
        }

        /* ---------- Phase 1: 对每个明细执行前操作 ---------- */
        int successCount = 0, skippedCount = 0, failedCount = 0;
        // 存储通过 preCheck 的明细信息，供落库阶段使用
        List<BatchFileInfo> passedFiles = new ArrayList<>();

        // 提取可复用对象：FileConverter 基于 parserType 查表，所有文件共享
        FileConverter converter = converterFactory.get(config.getParserType());

        for (Map<String, Object> detail : details) {
            FilePreCheckResult preResult = checkDetailBeforeTruncate(command, config, detail, nodeId, converter);
            if (preResult.passed()) {
                passedFiles.add(new BatchFileInfo(
                        detail, preResult.fileInfo(), preResult.auditCount()));
                successCount++;
            } else if (preResult.skipped()) {
                skippedCount++;
            } else {
                failedCount++;
            }
        }

        // 检查：如果所有文件都跳过，直接返回
        if (successCount == 0 && failedCount == 0) {
            result.setOutcome(0, ColumnNames.STATUS_SKIPPED, "All files skipped");
            return;
        }

        /* ---------- Phase 2: 清表 ---------- */
        if (BooleanUtils.isYes(config.getClearTableFlag())) {
            support.truncateTable(config);
        }

        /* ---------- Phase 3: 顺序落库（仅 preCheck 通过的文件） ---------- */
        // 预构建基础 FieldMapping（tableFields + config），所有明细共享
        // 每个明细的 extraFields 通过 attachExtraFields 单独附加
        FieldMapping baseMapping = fieldMappingBuilder.buildForUploadBase(config);
        int totalRecords = 0;
        int insertFailCount = 0;
        ResolvedPath lastFileInfo = null;
        for (BatchFileInfo bfi : passedFiles) {
            try {
                int recordCount = insertSingleDetail(ftpName, bfi, config, baseMapping);
                totalRecords += recordCount;
                lastFileInfo = bfi.fileInfo();
                // 更新明细表状态为成功
                updateDetailStatus(bfi.detail(), nodeId, ColumnNames.STATUS_SUCCESS);
            } catch (Exception e) {
                log.error("Insert failed for detail file: {}", bfi.fileInfo(), e);
                insertFailCount++;
                // 更新明细表状态为失败
                updateDetailStatus(bfi.detail(), nodeId, ColumnNames.STATUS_ERROR);
            }
        }
        failedCount += insertFailCount;

        /* ---------- Phase 4: 汇总后操作 ---------- */
        ResolvedPath finalLastFileInfo = lastFileInfo;
        int finalTotalRecords = totalRecords;
        try {
            transferSupport.executeWithClient(ftpName, client -> {
                transferSupport.postProcess(client, config, finalLastFileInfo,
                        Map.of("C", String.valueOf(finalTotalRecords)));
                return null;
            });
        } catch (Exception e) {
            log.error("Phase 4 (postProcess) failed for batch upload: {}", e.getMessage(), e);
            result.setOutcome(totalRecords, ColumnNames.STATUS_ERROR,
                    "Data uploaded successfully (" + totalRecords + " records) but post-processing failed: "
                            + e.getMessage());
            return;
        }

        result.setOutcome(totalRecords,
                TransferSupport.determineMainStatus(failedCount == 0 && skippedCount == 0, failedCount, skippedCount), "");
    }

    // ==================== BATCH 模式前检查（单明细，无事务） ====================

    /**
     * 对单个明细文件执行前检查（仅 preCheck），不涉及数据库写入。
     *
     * <p>明细的 {@code fileName} 通过 {@code FILE_NAME} 放入上下文，
     * 替换配置路径中的 {@code {FILE_NAME}} 占位符，得到具体文件路径。</p>
     *
     * <p>前稽核已合并到 {@link #insertSingleDetail} 的 insertDataAndVerify 中，
     * 在落库后与后审计使用 CloseableIterator.getRecordCount() 统一校验。</p>
     *
     * @param converter 文件格式转换器（由调用方传入，避免重复从工厂获取）
     * @return 前检查结果（通过/跳过/失败），已更新明细表状态
     */
    private FilePreCheckResult checkDetailBeforeTruncate(Command command, TransferConfig config,
                                                         Map<String, Object> detail, String nodeId,
                                                         FileConverter converter) {
        Long detailId = ((Number) detail.get(ColumnNames.DETAIL_ID)).longValue();
        String fileName = (String) detail.get(ColumnNames.FILE_NAME);

        if (fileName == null || fileName.isEmpty()) {
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_SKIPPED, nodeId);
            return FilePreCheckResult.skipped(null);
        }

        String detailFieldName = (String) detail.get(ColumnNames.FIELD_NAME);
        String detailFieldValue = (String) detail.get(ColumnNames.FIELD_VALUE);
        Map<String, String> detailContext = transferSupport.buildContext(
                command, detailFieldName, detailFieldValue);
        // 把明细文件名放入上下文，替换路径中的 {FILE_NAME} 占位符
        detailContext.put("FILE_NAME", fileName);
        ResolvedPath fileInfo = transferSupport.resolveFilePath(
                config.getFilePath(), detailContext);
        String filePath = fileInfo.fullPath();

        Integer detailAuditCount = detail.get(ColumnNames.AUDIT_COUNT) != null
                ? ((Number) detail.get(ColumnNames.AUDIT_COUNT)).intValue() : null;

        try {
            return transferSupport.executeWithClient(config.getFtpName(), client -> {
                // preCheck — 标志文件检查（前稽核在 insertDataAndVerify 落库后统一校验）
                UploadSupport.UploadResult checkResult = support.preCheck(client, config, fileInfo, filePath);
                if (checkResult != null) {
                    detailRepository.updateStatus(detailId, ColumnNames.STATUS_SKIPPED, nodeId);
                    return FilePreCheckResult.skipped(filePath);
                }

                // 检查通过（前稽核在 insertDataAndVerify 的落库后与后审计同时校验）
                return FilePreCheckResult.passed(filePath, fileInfo, detailAuditCount);
            });
        } catch (FlagCheckException e) {
            moveFileToError(config.getFtpName(), filePath, config);
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_ERROR, nodeId);
            log.warn("FLAG check failed, detail file: {}, detail: {}", filePath, e.getMessage());
            return FilePreCheckResult.failed(filePath, e.getMessage());
        } catch (Exception e) {
            detailRepository.updateStatus(detailId, ColumnNames.STATUS_ERROR, nodeId);
            log.error("Pre-check failed for detail file {}: {}", filePath, e.getMessage(), e);
            return FilePreCheckResult.failed(filePath, e.getMessage());
        }
    }

    // ==================== 落库阶段（单文件） ====================

    /**
     * 对单个文件执行落库操作（insertDataAndVerify + postProcess）。
     *
     * <p>此方法在清表之后调用，仅对前检查通过的文件执行。</p>
     *
     * <p>SERIAL 模式（目录通配符）下 auditCount 传入 null，跳过稽核数校验。
     * BATCH 模式（明细表指定文件）下 auditCount 由明细行传入，在落库后校验。</p>
     *
     * @param mapping    预构建的 FieldMapping（SERIAL 模式所有文件共享，避免重复构建）
     * @param auditCount 稽核数（SERIAL 模式传 null；BATCH 模式传明细对应的稽核数）
     * @return 成功插入的记录数
     */
    private Integer insertSingleFile(String ftpName, String filePath, ResolvedPath fileInfo,
                                     TransferConfig config, FieldMapping mapping, Integer auditCount) {
        try {
            return transferSupport.executeWithClient(ftpName, client -> {
                // 落库 + 前后审计（单事务），使用预构建的 FieldMapping（避免重复查表元数据）
                // auditCount 由 insertAndVerifyPerFileInTx 在落库后与后审计同时校验
                int count = support.insertDataAndVerify(client, config, mapping, filePath, auditCount);

                // 文件级后操作
                support.postProcess(client, config, fileInfo, count);

                log.info("Uploaded file: {} ({} records)", filePath, count);
                return count;
            });
        } catch (Exception e) {
            log.error("Insert failed for {}: {}", filePath, e.getMessage(), e);
            return TASK_FAIL;
        }
    }

    /**
     * 对单个明细文件执行落库操作（insertDataAndVerify + postProcess）。
     *
     * <p>前稽核与后审计在 insertDataAndVerify 的落库后使用
     * CloseableIterator.getRecordCount() 统一校验。</p>
     *
     * @return 成功插入的记录数
     */
    private int insertSingleDetail(String ftpName, BatchFileInfo bfi, TransferConfig config,
                                FieldMapping baseMapping) {
        try {
            return transferSupport.executeWithClient(ftpName, client -> {
                // 在基础映射上附加该明细的 extraFields（避免每明细重复查表元数据）
                Map<String, Object> detailContext = bfi.detail();
                FieldMapping mapping = fieldMappingBuilder.attachExtraFields(baseMapping, detailContext);
                int count = support.insertDataAndVerify(client, config, mapping, bfi.fileInfo().fullPath(), bfi.auditCount());

                // 文件级后操作
                support.postProcess(client, config, bfi.fileInfo(), count);

                log.info("Uploaded detail file: {} ({} records)", bfi.fileInfo().fullPath(), count);
                return count;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Insert failed for detail file {}: {}", bfi.fileInfo().fullPath(), e.getMessage(), e);
            throw new RuntimeException("Insert failed for " + bfi.fileInfo().fullPath(), e);
        }
    }

    // ==================== 预扫描工具 ====================

    /**
     * 从前置操作字符串中提取 FLAG 路径模式。
     * 例如 "FLAG:{stem}.OK,READY:other" → "{stem}.OK"。
     */
    static String extractFlagPathPattern(String preOps) {
        if (preOps == null || preOps.isEmpty()) return null;
        for (String op : preOps.split(",")) {
            op = op.trim();
            String pathPart = null;
            if (op.startsWith("FLAG:")) {
                pathPart = op.substring(5).trim();
            } else if (op.startsWith("READY:")) {
                pathPart = op.substring(6).trim();
            }
            if (pathPart == null || pathPart.isEmpty()) continue;
            int semicolon = pathPart.indexOf(';');
            return semicolon > 0 ? pathPart.substring(0, semicolon).trim() : pathPart;
        }
        return null;
    }

    /**
     * 预扫描文件列表，过滤出有效的数据文件。
     *
     * <p>对于有数据文件但无对应标志文件的，日志告警并跳过。</p>
     */
    List<String> prescanDataFiles(String[] allFiles, String flagPattern) {
        if (allFiles == null || allFiles.length == 0) return List.of();
        if (flagPattern == null) {
            return new ArrayList<>(List.of(allFiles));
        }

        Set<String> allNames = new HashSet<>();
        for (String f : allFiles) {
            allNames.add(fileNameOnly(f));
        }

        List<String> dataFiles = new ArrayList<>();
        for (String file : allFiles) {
            String name = fileNameOnly(file);

            String expectedFlagName = resolveFlagName(flagPattern, ResolvedPath.of(file));
            if (expectedFlagName == null) {
                dataFiles.add(file);
                continue;
            }

            String expectedName = fileNameOnly(expectedFlagName);
            if (name.equals(expectedName)) {
                continue;
            }
            if (allNames.contains(expectedName)) {
                dataFiles.add(file);
            } else {
                log.warn("Data file {} has no corresponding flag file (expected: {}), skipping",
                        file, expectedFlagName);
            }
        }
        return dataFiles;
    }

    static String resolveFlagName(String flagPattern, ResolvedPath fileInfo) {
        if (flagPattern == null || fileInfo == null) return null;
        String resolved = TransferSupport.expandPathVariables(flagPattern, fileInfo);
        if (!resolved.startsWith("/") && fileInfo.dir() != null && !fileInfo.dir().isEmpty()) {
            resolved = fileInfo.dir() + "/" + resolved;
        }
        return resolved;
    }

    private static String fileNameOnly(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * 单次 FTP 连接内完成：文件列表 + 标志检查 + 异常文件处理。
     *
     * <p>合并了原来的 {@code listFiles} + {@code prescanDataFiles} + 逐文件 preCheck，
     * 减少 FTP 连接次数（从 1+N 次降至 1 次）。</p>
     *
     * <p>处理逻辑：</p>
     * <ul>
     *   <li>数据文件有对应标志 → 加入有效列表</li>
     *   <li>数据文件无对应标志 → 跳过（仅日志警告）</li>
     *   <li>孤立的标志文件（无对应数据文件）→ 迁到 error 目录（自动重命名 + 时间戳）</li>
     * </ul>
     *
     * @param ftpName     FTP 连接名
     * @param listPattern 文件列表通配符模式
     * @param config      传输配置
     * @return 有效数据文件完整路径列表；null 表示列表失败
     */
    private List<String> prescanAndValidate(String ftpName, String listPattern,
                                             TransferConfig config) throws Exception {
        return transferSupport.executeWithClient(ftpName, client -> {
            // 1. 列出所有文件
            String[] allFiles = client.listFiles(listPattern);
            if (allFiles == null) return null;
            if (allFiles.length == 0) return List.of();
            log.debug("Prescan: {} files listed (pattern: {})", allFiles.length, listPattern);

            // 2. 提取标志模式
            String flagPattern = extractFlagPathPattern(config.getPreOperations());
            if (flagPattern == null) {
                // 无标志检查 → 所有文件都是数据文件
                log.info("Prescan: {} files (no flag pattern)", allFiles.length);
                return new ArrayList<>(List.of(allFiles));
            }

            // 3. 构建文件名集合
            Set<String> allNames = new HashSet<>();
            for (String f : allFiles) allNames.add(fileNameOnly(f));

            // 4. 分类处理
            List<String> validDataFiles = new ArrayList<>();
            List<String> noFlagDataFiles = new ArrayList<>();
            List<String> flagFiles = new ArrayList<>();
            Set<String> validFlagNames = new HashSet<>();

            for (String file : allFiles) {
                String name = fileNameOnly(file);
                String expectedFlagName = resolveFlagName(flagPattern, ResolvedPath.of(file));
                if (expectedFlagName == null) {
                    validDataFiles.add(file);
                    continue;
                }
                String expectedName = fileNameOnly(expectedFlagName);

                if (name.equals(expectedName)) {
                    // 这是标志文件本身
                    flagFiles.add(file);
                    continue;
                }

                if (allNames.contains(expectedName)) {
                    // 数据文件有对应标志 → 有效
                    validDataFiles.add(file);
                    validFlagNames.add(expectedName);
                } else {
                    // 数据文件无对应标志 → 跳过
                    noFlagDataFiles.add(file);
                }
            }

            // 5. 日志警告：无标志的数据文件（跳过，不迁 error）
            for (String badFile : noFlagDataFiles) {
                log.warn("Data file without flag, skipping: {}", badFile);
            }

            // 6. 处理孤立的标志文件（迁到 error 目录，自动重命名 + 时间戳）
            int orphanedCount = 0;
            for (String flagFile : flagFiles) {
                String name = fileNameOnly(flagFile);
                if (!validFlagNames.contains(name)) {
                    log.warn("Orphaned flag file, moving to error: {}", flagFile);
                    try {
                        client.moveToErrorDir(flagFile);
                        orphanedCount++;
                    } catch (Exception e) {
                        log.error("Failed to move orphaned flag to error: {}", flagFile, e);
                    }
                }
            }

            log.info("Prescan: {} valid, {} skipped (no flag), {} orphaned flags moved to error",
                    validDataFiles.size(), noFlagDataFiles.size(), orphanedCount);

            return validDataFiles;
        });
    }

    // ==================== 通用工具 ====================

    private static void shutdownExecutor(ExecutorService executor) {
        TransferUtils.shutdownExecutor(executor, 1, TimeUnit.MINUTES, "Upload executor");
    }

    private void rollbackOnFailure(TransferConfig config, int failedCount) throws TransferException {
        if (BooleanUtils.isYes(config.getClearTableFlag())) {
            support.truncateTable(config);
            log.error("Rolled back table {} due to {} failed file(s)",
                    config.getTableName(), failedCount);
        } else {
            log.error("Post-audit failed for {} file(s); incremental mode, preserving existing data in {}.{}",
                    failedCount, config.getDbName(), config.getTableName());
        }
        throw new TransferException("POST_AUDIT_FAILED",
                "Post-audit failed for " + failedCount + " file(s)");
    }

    private void moveFileToError(String ftpName, String filePath, TransferConfig config) {
        try {
            transferSupport.executeWithClient(ftpName, client -> {
                support.moveDataAndFlagToErrorDir(client, filePath, config);
                return null;
            });
        } catch (Exception ex) {
            log.error("Failed to move files to error dir for {}: {}", filePath, ex.getMessage());
        }
    }

    private void updateDetailStatus(Map<String, Object> detail, String nodeId, String status) {
        Long detailId = ((Number) detail.get(ColumnNames.DETAIL_ID)).longValue();
        detailRepository.updateStatus(detailId, status, nodeId);
    }

    // ==================== 内部值类型 ====================

    /**
     * 前检查结果（preCheck 的结果）。
     */
    record FilePreCheckResult(String filePath, boolean passed, boolean skipped, boolean failed,
                              ResolvedPath fileInfo, Integer auditCount, String errorMessage) {

        static FilePreCheckResult passed(String filePath, ResolvedPath fileInfo, Integer auditCount) {
            return new FilePreCheckResult(filePath, true, false, false, fileInfo, auditCount, null);
        }

        static FilePreCheckResult skipped(String filePath) {
            return new FilePreCheckResult(filePath, false, true, false, null, null, null);
        }

        static FilePreCheckResult failed(String filePath, String errorMessage) {
            return new FilePreCheckResult(filePath, false, false, true, null, null, errorMessage);
        }
    }

    /**
     * BATCH 模式中通过前检查的明细文件信息。
     *
     * @param detail     明细行原始数据
     * @param fileInfo   解析后的文件路径信息
     * @param auditCount 该明细的稽核数
     */
    record BatchFileInfo(Map<String, Object> detail, ResolvedPath fileInfo, Integer auditCount) {}
}