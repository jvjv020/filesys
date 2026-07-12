package com.fmsy.transfer.upload;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.exception.FlagCheckException;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.BooleanUtils;
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
 * 上传场景 Support — 跨 3 个 Upload Handler 共享的上传协议方法。
 *
 * <p>4 个公共方法(resolveFilePath / preCheck / postProcess / handleEmptyData)已抽出到
 * {@link com.fmsy.transfer.TransferSupport},Handler 直接注入使用。
 * 本类只保留方向特有的:
 * <ul>
 *   <li>预审计:文件记录数 vs auditCount,使用 {@link FileConverter#countRecords} 按格式统计</li>
 *   <li>后审计:文件记录数 vs 插入记录数</li>
 *   <li>核心流式插入+后审计:{@link #insertAndVerifyPerFileInTx}</li>
 *   <li>完整单文件管线(含FTP借还+异常文件迁移):{@link #processSingleFile}</li>
 *   <li>跨场景状态判定:determineMainStatus + UploadResult 记录</li>
 * </ul>
 *
 * <p>所有上传场景统一走 {@link #insertAndVerifyPerFileInTx} 单事务插入(含后审计),
 * 清表与增量模式都适用,失败时全量回滚。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadSupport {

    private final FtpPool ftpPool;
    private final TargetTableRepository targetTableRepository;
    private final DataSourceConfig.DbPool dbPool;
    private final TransferSupport transferSupport;
    private final ConverterFactory converterFactory;
    private final FieldMappingBuilder fieldMappingBuilder;

    // ==================== 协议方法(方向特有) ====================

    /**
     * 预审计:文件记录数 vs auditCount(Upload 语义)
     *
     * <p>使用格式对应的 {@link FileConverter#countRecords} 统计文件记录数,
     * 而非通用行数统计(CSV/TXT 逐行、DBF 从文件头读、XML 返回 -1)。
     *
     * <p>当 auditCount 为 null/负数(表示不校验)时,跳过 FTP 文件流打开,
     * 避免对无法统计记录数的格式(如 XML)做无谓的 I/O。
     *
     * @param auditCount 期望行数(null 或负数表示不校验)
     * @param config     传输配置
     * @param filePath   FTP 文件路径
     * @param converter  文件格式转换器(用于按格式统计记录数)
     * @return 0 表示通过或无需审计, -1 表示审计不通过或审计异常
     */
    public int preAudit(Integer auditCount, TransferConfig config, String filePath, FileConverter converter) {
        // 未配置审计 → 无需打开文件流
        if (auditCount == null || auditCount < 0) {
            return 0;
        }
        try {
            int recordCount = ftpPool.withClient(config.getFtpName(), client -> {
                try (InputStream is = client.getInputStream(filePath)) {
                    return converter.countRecords(is, null);
                }
            });
            if (recordCount < 0) {
                // 格式无法统计记录数(如 XML),跳过前稽核而非报错退出
                log.warn("Pre-audit: unable to count records for {} format, skipping pre-audit", converter.getFormat());
                return 0;
            }
            boolean passed = recordCount == auditCount;
            log.info("Pre-audit file records: expected={}, actual={}, passed={}", auditCount, recordCount, passed);
            if (!passed) {
                log.error("Pre-audit failed: expected {} records, got {} for file {}", auditCount, recordCount, filePath);
                return -1;
            }
            return 0;
        } catch (Exception e) {
            log.error("Pre-audit failed for {}: {}", filePath, e.getMessage(), e);
            return -1;
        }
    }

    /**
     * 后审计:本次插入记录数 vs 文件记录数(Upload 语义)
     *
     * <p>比对的是"文件记录数"与"成功插入的记录数",而非全表 COUNT。
     * 多文件场景下,全表 COUNT 包含其他文件的数据,对比无意义。
     *
     * @param config        传输配置
     * @param fileLineCount 该文件的记录行数(由 preAudit 返回)
     * @param insertedCount 本次成功插入的记录数
     * @return true=审计通过
     */
    public boolean postAudit(TransferConfig config, int fileLineCount, int insertedCount) {
        boolean passed = fileLineCount == insertedCount;
        log.info("Post-audit upload: fileRecords={}, inserted={}, passed={}",
                fileLineCount, insertedCount, passed);
        return passed;
    }

    // ==================== 核心流式插入(单事务) ====================

    /**
     * 在事务中执行数据插入 — 提取自 3 个 insert* 方法的公共批量插入循环。
     *
     * <p>清表(truncateFirst)和空数据处理(handleEmptyData)在事务内完成,
     * 失败时全部回滚,不影响其他文件。</p>
     *
     * @return 成功插入的记录数
     */
    private int executeInsertInTx(TransferConfig config, Iterator<List<Map<String, Object>>> dataIter,
                                  FieldMapping mapping, List<String> fields, boolean truncateFirst) {
        if (truncateFirst) {
            targetTableRepository.truncate(config.getDbName(), config.getTableName());
        }
        int batchSize = SystemConstants.DEFAULT_BATCH_SIZE;
        List<Object[]> batch = new ArrayList<>();
        int totalRecords = 0;

        while (dataIter.hasNext()) {
            List<Map<String, Object>> batchChunk = dataIter.next();
            if (batchChunk.isEmpty()) continue;

            for (Map<String, Object> record : batchChunk) {
                Object[] values = new Object[fields.size()];
                for (int i = 0; i < fields.size(); i++) {
                    values[i] = mapping.getValue(record, fields.get(i));  // uses outer mapping
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

        // 空数据判断在事务内,ERROR/SKIP 模式直接回滚
        if (!transferSupport.handleEmptyData(totalRecords, config.getEmptyDataHandling())) {
            throw new RuntimeException("Empty data handling: " + config.getEmptyDataHandling());
        }

        return totalRecords;
    }

    /**
     * 在事务中执行插入 + 后审计 — 适用于多文件场景(目录通配符/明细表)的每个文件。
     *
     * <p>后审计失败时:
     * <ul>
     *   <li>ERROR 模式:抛出异常触发事务回滚,已插入数据全部撤销</li>
     *   <li>非 ERROR 模式:数据已提交,返回 -1 供调用方标记该文件失败</li>
     * </ul>
     *
     * @param config        传输配置
     * @param dataIter      数据迭代器(需支持 {@link CloseableIterator#getRecordCount()})
     * @param mapping       字段映射
     * @param truncateFirst 是否先清表
     * @return 成功插入的记录数, -1 表示后审计失败(非ERROR模式,数据已提交)
     */
    public int insertAndVerifyPerFileInTx(TransferConfig config,
                                           CloseableIterator<List<Map<String, Object>>> dataIter,
                                           FieldMapping mapping, boolean truncateFirst) {
        String dbName = config.getDbName();
        TransactionTemplate tx = dbPool.getTransactionTemplate(dbName);
        List<String> fields = mapping.getTableFields();

        return tx.execute(status -> {
            int totalRecords = executeInsertInTx(config, dataIter, mapping, fields, truncateFirst);

            // 后审计在事务内完成,ERROR 模式可回滚未提交的数据
            int fileRecordCount = dataIter.getRecordCount();
            if (!postAudit(config, fileRecordCount, totalRecords)) {
                if (config.getEmptyDataHandling() == EmptyDataHandling.ERROR) {
                    throw new RuntimeException("Post-audit failed: file records=" + fileRecordCount
                            + ", inserted=" + totalRecords + " for " + config.getTableName());
                }
                log.warn("Post-audit failed (non-ERROR mode): file records={}, inserted={}, data kept",
                        fileRecordCount, totalRecords);
                return -1;
            }

            log.debug("Inserted {} records in single transaction (with verify)", totalRecords);
            return totalRecords;
        });
    }

    /**
     * 清表操作 — 在事务中清空目标表。
     *
     * <p>由 SingleUploadHandler / MultiDirectoryUploadHandler / MultiBatchUploadHandler
     * 在 clearTableFlag=Y 时调用，避免各 Handler 直接依赖 TargetTableRepository 和 DbPool。
     */
    public void truncateTable(TransferConfig config) {
        dbPool.getTransactionTemplate(config.getDbName()).execute(status -> {
            targetTableRepository.truncate(config.getDbName(), config.getTableName());
            return null;
        });
    }

    /**
     * 单文件完整上传流程 — 前置检查 → 前稽核 → 插入 → 后稽核 → 后操作。
     *
     * <p>异常由调用方处理:
     * <ul>
     *   <li>{@link FlagCheckException} (preCheck FLAG比对失败) → 调用方负责迁数据文件+标志文件到error目录</li>
     *   <li>其他运行时异常(preAudit/insert/postAudit/postProcess失败) → 调用方负责迁文件+标记ERROR</li>
     *   <li>preCheck返回false(标志文件不存在) → 返回UploadResult.SKIPPED，调用方仅告警跳过，不迁移文件</li>
     * </ul>
     *
     * @param client          已借出的FTP客户端（由调用方管理close）
     * @param command         指令对象（取auditCount等）
     * @param config          传输配置
     * @param filePath        数据文件FTP路径
     * @param detailContext   明细上下文（可为null，供{FIELD_NAME}占位符解析用）
     * @return 单文件UploadResult，status=null表示成功，SKIPPED表示跳过，ERROR表示失败
     */
    public UploadResult uploadSingleFile(FtpClient client, Integer auditCount, TransferConfig config,
                                          String filePath, Map<String, Object> detailContext) {
        ResolvedPath fileInfo = ResolvedPath.of(filePath);

        // Phase 1: preCheck — 标志文件不存在返回false（跳过），比对失败抛FlagCheckException
        try {
            if (!transferSupport.preCheck(client, config, fileInfo)) {
                // 标志文件不存在 → 告警跳过，不迁移文件
                log.warn("Pre-check failed (flag not found) for: {}", filePath);
                return new UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED);
            }
        } catch (FlagCheckException e) {
            // FLAG比对失败 → 抛异常，由调用方负责迁数据文件+标志文件到error目录
            throw e;
        }

        // Phase 2: preAudit
        FileConverter converter = converterFactory.get(config.getParserType());
        int preAuditResult = preAudit(auditCount, config, filePath, converter);
        if (preAuditResult < 0) {
            // preAudit失败 → 迁文件，抛异常供调用方标记ERROR
            throw new RuntimeException("Pre-audit failed for: " + filePath);
        }

        // Phase 2: read & insert
        // 构建字段映射（detailContext非空时取明细中的字段）
        FieldMapping mapping = fieldMappingBuilder.buildForUpload(config, detailContext);
        try (InputStream is = client.getInputStream(filePath);
             CloseableIterator<List<Map<String, Object>>> dataIter =
                     new CloseableIterator<>(converter.parse(is, mapping))) {

            boolean truncateFirst = BooleanUtils.isYes(config.getClearTableFlag());
            int count = insertAndVerifyPerFileInTx(config, dataIter, mapping, truncateFirst);
            if (count < 0) {
                // 非ERROR模式后审计失败 → 迁文件，抛异常
                throw new RuntimeException("Post-audit failed for: " + filePath);
            }

            // Phase 3: postProcess
            try {
                transferSupport.postProcess(client, config, fileInfo, count);
            } catch (Exception e) {
                // postProcess失败不影响结果状态，仅记录
                log.warn("Post-process failed for {}: {}", filePath, e.getMessage());
            }

            return new UploadResult(count, 1, 0, 0, null);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Upload failed for " + filePath + ": " + e.getMessage(), e);
        }
    }

    // ==================== 单文件处理复用方法 ====================

    /**
     * 处理单个文件上传的完整流程 — 包括 FTP 客户端借还、上传管线、错误处理（文件迁移）。
     *
     * <p>本方法封装了以下步骤:
     * <ol>
     *   <li>通过 {@link TransferSupport#executeWithClient} 借还 FTP 客户端</li>
     *   <li>调用 {@link #uploadSingleFile} 执行上传管线(preCheck→preAudit→insert→postAudit→postProcess)</li>
     *   <li>异常处理:FLAG 比对失败/preAudit失败/后审计失败时,自动将数据文件及配置的标志文件迁到 error 目录</li>
     * </ol>
     *
     * <p>调用方无需再处理 FTP 借还和文件迁移,只需根据返回的 {@link UploadResult} 进行业务处理
     * （如更新明细表状态、设置结果表状态等）。
     *
     * <p>本方法不抛出异常 — 所有错误都编码到 {@link UploadResult#status()} 中。
     *
     * @param ftpName       FTP 连接名
     * @param auditCount    稽核数（可为 null）
     * @param config        传输配置
     * @param filePath      数据文件 FTP 路径
     * @param detailContext 明细上下文（可为 null,供 {FIELD_NAME} 占位符解析用）
     * @return 上传结果,status=null 表示成功,SKIPPED 表示跳过,ERROR 表示失败
     */
    public UploadResult processSingleFile(String ftpName, Integer auditCount, TransferConfig config,
                                          String filePath, Map<String, Object> detailContext) {
        try {
            return transferSupport.executeWithClient(ftpName, client -> {
                try {
                    UploadResult r = uploadSingleFile(client, auditCount, config, filePath, detailContext);
                    // SKIPPED 和 null 均直接返回(异常在 uploadSingleFile 中以抛异常表达)
                    return r;
                } catch (FlagCheckException e) {
                    // FLAG 比对失败 → 迁数据文件+标志文件
                    moveDataAndFlagToErrorDir(client, filePath, config);
                    log.warn("FLAG check failed for {}: {}", filePath, e.getMessage());
                    return new UploadResult(0, 0, 0, 1, ColumnNames.STATUS_ERROR);
                } catch (RuntimeException e) {
                    // preAudit 失败/后审计失败 → 迁数据文件+标志文件
                    moveDataAndFlagToErrorDir(client, filePath, config);
                    log.error("Upload failed for {}: {}", filePath, e.getMessage());
                    return new UploadResult(0, 0, 0, 1, ColumnNames.STATUS_ERROR);
                }
            });
        } catch (Exception e) {
            // executeWithClient 自身抛出的异常（极少的连接层异常）
            log.error("Unexpected error uploading {}: {}", filePath, e.getMessage(), e);
            return new UploadResult(0, 0, 0, 1, ColumnNames.STATUS_ERROR);
        }
    }

    /**
     * 从传输配置的前置操作中提取标志文件的路径。
     *
     * <p>解析 preOperations 字符串,找到第一个 FLAG 或 READY 操作,提取路径模式
     * 并解析文件衍生变量({@code {stem}}, {@code {name}}, {@code {ext}}, {@code {dir}} 等)。
     *
     * @param preOps  前置操作字符串,如 {@code "FLAG:{stem}.OK"} 或 {@code "READY:{stem}.ready"}
     * @param fileInfo 数据文件的路径信息,用于解析文件衍生变量
     * @return 解析后的标志文件完整路径；没有 FLAG/READY 操作时返回 null
     */
    public static String resolveConfiguredFlagPath(String preOps, ResolvedPath fileInfo) {
        if (preOps == null || preOps.isEmpty() || fileInfo == null) return null;
        for (String op : preOps.split(",")) {
            op = op.trim();
            String pathPart;
            if (op.startsWith("FLAG:")) {
                pathPart = op.substring(5).trim();
            } else if (op.startsWith("READY:")) {
                pathPart = op.substring(6).trim();
            } else {
                continue;
            }
            if (pathPart.isEmpty()) continue;

            // 去掉模式后缀（;mode 部分）
            int semicolon = pathPart.indexOf(';');
            String pathPattern = semicolon > 0 ? pathPart.substring(0, semicolon).trim() : pathPart;

            // 展开文件衍生变量
            String resolved = expandPathVariables(pathPattern, fileInfo);

            // 相对路径 → 继承数据文件的目录
            if (!resolved.startsWith("/") && fileInfo.dir() != null && !fileInfo.dir().isEmpty()) {
                resolved = fileInfo.dir() + "/" + resolved;
            }

            // 规范化 .. 段
            return normalizePathSlashes(resolved);
        }
        return null;
    }

    /**
     * 替换路径模板中的文件衍生变量。
     */
    private static String expandPathVariables(String template, ResolvedPath fileInfo) {
        if (template == null || fileInfo == null) return template;
        return template
                .replace("{stem}", fileInfo.stem() != null ? fileInfo.stem() : "")
                .replace("{name}", fileInfo.name() != null ? fileInfo.name() : "")
                .replace("{ext}", fileInfo.ext() != null ? fileInfo.ext() : "")
                .replace("{dir}", fileInfo.dir() != null ? fileInfo.dir() : "")
                .replace("{dn}", fileInfo.dn() != null ? fileInfo.dn() : "")
                .replace("{up}", fileInfo.up() != null ? fileInfo.up() : "");
    }

    /**
     * 规范化路径中的 ".." 段。
     */
    static String normalizePathSlashes(String path) {
        if (path == null || !path.contains("..")) return path;
        String[] segments = path.split("/");
        ArrayList<String> result = new ArrayList<>();
        for (String seg : segments) {
            if ("..".equals(seg)) {
                if (!result.isEmpty()) result.remove(result.size() - 1);
            } else if (!seg.isEmpty()) {
                result.add(seg);
            }
        }
        return "/" + String.join("/", result);
    }

    /**
     * 将数据文件及配置的标志文件一起迁到 error 目录。
     *
     * <p>数据文件迁移委托给 {@link FtpClient#moveToErrorDir}（含时间戳重命名及通用标志文件后缀的迁移）。
     * 同时根据 preOperations 中的 FLAG/READY 路径,将配置的标志文件也迁到 error 目录。
     */
    private void moveDataAndFlagToErrorDir(FtpClient client, String filePath, TransferConfig config) {
        // 1. 迁数据文件（FtpClient 本身已处理 .OK/.ready/.flag 等通用后缀）
        try {
            client.moveToErrorDir(filePath);
        } catch (Exception e) {
            log.error("Failed to move error file {}: {}", filePath, e.getMessage());
        }
        // 2. 迁配置的标志文件（从 preOperations 中解析 FLAG/READY 路径）
        try {
            String flagPath = resolveConfiguredFlagPath(config.getPreOperations(), ResolvedPath.of(filePath));
            if (flagPath != null && !flagPath.isEmpty() && !flagPath.equals(filePath)) {
                if (client.exists(flagPath)) {
                    client.moveToErrorDir(flagPath);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to move configured flag file for {}: {}", filePath, e.getMessage());
        }
    }

    // ==================== 跨场景主状态判定 ====================

    /**
     * 单文件上传结果。
     * @param records     成功插入的记录数（跳过或失败时为0）
     * @param successCount 成功文件数
     * @param skippedCount 跳过文件数
     * @param failedCount  失败文件数
     * @param status      null=成功, SKIPPED=跳过, ERROR=失败
     */
    public record UploadResult(int records, int successCount, int skippedCount, int failedCount,
                               String status) {
        public static UploadResult allSkipped() { return new UploadResult(0, 0, 1, 0, ColumnNames.STATUS_SKIPPED); }
    }

    public static String determineMainStatus(UploadResult result) {
        boolean allSuccess = result.failedCount() == 0 && result.skippedCount() == 0;
        return TransferSupport.determineMainStatus(allSuccess, result.failedCount(), result.skippedCount());
    }

}
