package com.fmsy.transfer.upload;

import com.fmsy.converter.CloseableIterator;
import com.fmsy.converter.ConverterFactory;
import com.fmsy.converter.FileConverter;
import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.exception.TransferException;
import com.fmsy.ftp.FtpClient;
import com.fmsy.ftp.FtpPool;
import com.fmsy.model.Command;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.FieldMappingBuilder;
import com.fmsy.transfer.TransferSupport;
import com.fmsy.util.ResolvedPath;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 多文件目录上传的单文件任务 — 在 {@link MultiDirectoryUploadHandler} 的并发池中并行执行。
 *
 * <p>返回值约定:
 * <ul>
 *   <li>&gt; 0:成功,值是插入的记录数</li>
 *   <li>{@link #SKIP} = -1:pre-check 失败,文件被跳过</li>
 *   <li>{@link #FAIL} = -2:其他异常,文件失败</li>
 * </ul>
 *
 * <p>所有异常都在内部 catch 并转换为 SKIP/FAIL 数值,不会向调用方抛出。
 */
@Slf4j
public class DirectoryUploadTask implements Callable<Integer> {

    public static final int SKIP = -1;
    public static final int FAIL = -2;

    private final String filePath;
    private final FtpPool ftpPool;
    private final String ftpName;
    private final TransferConfig config;
    private final Command command;
    private final TransferSupport transferSupport;
    private final UploadSupport uploadSupport;
    private final FieldMappingBuilder fieldMappingBuilder;

    public DirectoryUploadTask(String filePath, FtpPool ftpPool, String ftpName,
                               TransferConfig config, Command command,
                               TransferSupport transferSupport, UploadSupport uploadSupport,
                               FieldMappingBuilder fieldMappingBuilder) {
        this.filePath = filePath;
        this.ftpPool = ftpPool;
        this.ftpName = ftpName;
        this.config = config;
        this.command = command;
        this.transferSupport = transferSupport;
        this.uploadSupport = uploadSupport;
        this.fieldMappingBuilder = fieldMappingBuilder;
    }

    public String getFilePath() {
        return filePath;
    }

    @Override
    public Integer call() {
        FtpClient client = ftpPool.getClient(ftpName);
        try {
            ResolvedPath fileInfo = ResolvedPath.of(filePath);
            if (!transferSupport.preCheck(client, config, fileInfo)) {
                log.warn("Pre-check failed for file: {}", filePath);
                return SKIP;
            }

            // 先获取 converter(用于 preAudit 按格式统计行数)
            FileConverter converter = ConverterFactory.get(config.getParserType());
            int fileLineCount = uploadSupport.preAudit(command.getAuditCount(), config, filePath, converter);
            if (fileLineCount < 0) {
                log.warn("Pre-audit failed for file: {}", filePath);
                return FAIL;
            }

            try (InputStream is = client.getInputStream(filePath)) {
                FieldMapping mapping = fieldMappingBuilder.buildForUpload(config, null);

                try (CloseableIterator<List<Map<String, Object>>> dataIter =
                        new CloseableIterator<>(converter.parse(is, mapping))) {
                    int count = uploadSupport.insertBatchInTx(config, dataIter, mapping);
                    int actualFileRecords = dataIter.getRecordCount();

                    if (!uploadSupport.postAudit(config, actualFileRecords, count)) {
                        if (config.getEmptyDataHandling() == EmptyDataHandling.ERROR) {
                            throw new TransferException("POST_AUDIT_FAILED",
                                    "Post-audit failed for file: " + filePath);
                        }
                        return count;
                    }

                    log.info("Uploaded file: {}", filePath);
                    return count;
                }
            }
        } catch (Exception e) {
            log.error("Failed to upload file: {}", filePath, e);
            return FAIL;
        } finally {
            client.close();
        }
    }
}
