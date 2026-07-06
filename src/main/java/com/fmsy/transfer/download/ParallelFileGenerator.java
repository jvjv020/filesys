package com.fmsy.transfer.download;

import com.fmsy.converter.FileConverter;
import com.fmsy.db.PartitionHelper;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TargetTableRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并行文件生成器 — 按分区并行读取数据库并写入临时文件,主线程流水线式拼接。
 *
 * <p>设计模式:生产者-消费者(分区并行 → 流水线拼接)
 * <ul>
 *   <li>每个分区一个临时文件,提交到 3 线程池并行处理</li>
 *   <li>主线程:写文件头 → 按分区顺序 {@code Future.get()} 等待完成 → 立即拼接 → 写文件尾</li>
 *   <li>分区失败时:取消其余未开始的 {@code Future}({@code cancel(false)} 不中断已在运行的分区)</li>
 *   <li>最终输出可能为部分数据;由调用方的 postAudit 校验并回滚</li>
 *   <li>非分区表自动降级为串行模式,调用方无感知</li>
 * </ul>
 *
 * <p>DBF 特殊处理:由调用方预 COUNT 后传入 preCountedRecords,写文件头时直接填入,
 * 避免内部重复 COUNT。
 */
@Slf4j
@Service
public class ParallelFileGenerator {

    private static final String TEMP_DIR_PREFIX = "fmsy-parallel";

    private final TargetTableRepository targetTableRepository;
    private final PartitionHelper partitionHelper;
    private final int parallelThreads;

    public ParallelFileGenerator(TargetTableRepository targetTableRepository,
                                  PartitionHelper partitionHelper,
                                  com.fmsy.config.AppConfig appConfig) {
        this.targetTableRepository = targetTableRepository;
        this.partitionHelper = partitionHelper;
        this.parallelThreads = appConfig.getDownload().getParallelThreads();
    }

    /**
     * 生成文件 — 自动判断是否使用并行模式。
     *
     * @param output            最终输出流(FTP OutputStream)
     * @param config            传输配置
     * @param converter         文件转换器
     * @param mapping           字段映射
     * @param preCountedRecords DBF 预统计的记录数(其他格式传 0 即可)
     * @return 写入的总记录数
     */
    public int generate(OutputStream output, TransferConfig config,
                         FileConverter converter, FieldMapping mapping,
                         long preCountedRecords) {
        String dbName = config.getDbName();
        String tableName = config.getTableName();

        if (partitionHelper.isPartitioned(dbName, tableName)) {
            List<String> partitions = partitionHelper.getPartitions(dbName, tableName);
            if (partitions.size() >= 2) {
                return generateParallel(output, config, converter, mapping, partitions, preCountedRecords);
            }
        }

        return generateSerial(output, config, converter, mapping, preCountedRecords);
    }

    /**
     * 串行模式(降级)
     */
    private int generateSerial(OutputStream output, TransferConfig config,
                                FileConverter converter, FieldMapping mapping,
                                long preCountedRecords) {
        String dbName = config.getDbName();
        String tableName = config.getTableName();

        converter.writeHeader(output, mapping, preCountedRecords);

        Iterator<List<Map<String, Object>>> data = targetTableRepository.streamQueryBatches(
                dbName, tableName, null, false, null, null, null, null);
        int count = converter.writeDataRecords(output, data, mapping);

        converter.writeFooter(output, mapping);
        return count;
    }

    /**
     * 并行模式:
     * <ol>
     *   <li>每个分区一个临时文件,全部提交到线程池(3 线程并行调度)</li>
     *   <li>主线程写 header,然后按分区顺序 {@code Future.get()} 等待完成</li>
     *   <li>完成一个立即拼接一个到最终输出流</li>
     *   <li>任一分区失败 → {@code cancel(false)} 取消其余未开始的 Future
     *       (已在运行的分区不受影响,继续执行到完成,但其临时文件被丢弃)</li>
     * </ol>
     */
    private int generateParallel(OutputStream output, TransferConfig config,
                                  FileConverter converter, FieldMapping mapping,
                                  List<String> partitions, long preCountedRecords) {
        String dbName = config.getDbName();
        String tableName = config.getTableName();
        List<String> pkOrderBy = partitionHelper.getPrimaryKeyColumns(dbName, tableName);

        String tag = config.getCategoryCode() + "_" + config.getControlCode();
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), TEMP_DIR_PREFIX, tag);
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            log.warn("Parallel[{}] failed to create temp dir, fallback to serial: {}", tag, e.getMessage());
            return generateSerial(output, config, converter, mapping, preCountedRecords);
        }

        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
        AtomicInteger totalCount = new AtomicInteger(0);
        AtomicBoolean anyFailed = new AtomicBoolean(false);
        List<Path> tempFiles = new ArrayList<>(partitions.size());
        List<Future<?>> futures = new ArrayList<>(partitions.size());

        log.info("Parallel[{}] start: {} partitions, {} threads", tag, partitions.size(), parallelThreads);

        // === Phase 1: 提交所有分区任务 ===
        for (int i = 0; i < partitions.size(); i++) {
            String partitionName = partitions.get(i);
            Path tempFile = tempDir.resolve("part_" + i + ".tmp");
            tempFiles.add(tempFile);

            futures.add(executor.submit(() -> {
                try (OutputStream tempOut = Files.newOutputStream(tempFile)) {
                    Iterator<List<Map<String, Object>>> data = targetTableRepository.streamTableDirect(
                            dbName, partitionName, pkOrderBy);
                    int count = converter.writeDataRecords(tempOut, data, mapping);
                    totalCount.addAndGet(count);
                    log.debug("Parallel[{}] partition {} completed: {} records", tag, partitionName, count);
                } catch (Exception e) {
                    log.error("Parallel[{}] partition {} failed: {}", tag, partitionName, e.getMessage(), e);
                    throw e;
                }
            }));
        }

        executor.shutdown();

        // === Phase 2: 写 header ===
        converter.writeHeader(output, mapping, preCountedRecords > 0 ? preCountedRecords : 0);

        // === Phase 3: 流水线拼接 ===
        for (int i = 0; i < partitions.size(); i++) {
            String partitionName = partitions.get(i);
            Path tempFile = tempFiles.get(i);

            // 已有分区失败 → 取消未开始的 Future(已运行的让其完成,但丢弃其输出)
            if (anyFailed.get()) {
                Future<?> f = futures.get(i);
                if (!f.isDone()) {
                    if (f.cancel(false)) {
                        log.warn("Parallel[{}] partition {} cancelled (prior failure)", tag, partitionName);
                    } else {
                        log.warn("Parallel[{}] partition {} already running, letting it finish (output discarded)", tag, partitionName);
                    }
                }
                continue;
            }

            try {
                futures.get(i).get();
            } catch (CancellationException e) {
                log.warn("Parallel[{}] partition {} was cancelled", tag, partitionName);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                anyFailed.set(true);
                log.error("Parallel[{}] partition {} interrupted, abandoning remaining partitions", tag, partitionName);
                cancelUnstarted(futures, i + 1, partitions, tag);
                break;
            } catch (ExecutionException e) {
                anyFailed.set(true);
                log.error("Parallel[{}] partition {} failed: {}, abandoning remaining partitions",
                        tag, partitionName, e.getCause().getMessage());
                cancelUnstarted(futures, i + 1, partitions, tag);
                continue;
            }

            // 拼接该分区数据
            try {
                if (Files.size(tempFile) > 0) {
                    Files.copy(tempFile, output);
                }
                Files.delete(tempFile);
                log.debug("Parallel[{}] partition {} concatenated", tag, partitionName);
            } catch (IOException e) {
                log.warn("Parallel[{}] failed to concatenate partition {}: {}", tag, partitionName, e.getMessage());
            }
        }

        converter.writeFooter(output, mapping);

        if (anyFailed.get()) {
            log.error("Parallel[{}] completed WITH FAILURES: succeeded={}/{} partitions, {} records (output may be incomplete)",
                    tag, countCompleted(tempFiles), partitions.size(), totalCount.get());
        } else {
            log.info("Parallel[{}] completed: {} partitions, {} records", tag, partitions.size(), totalCount.get());
        }

        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Parallel[{}] some partition threads did not terminate within 5s", tag);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Parallel[{}] interrupted while waiting for partition thread termination", tag);
        }
        cleanupTempDir(tempDir);
        return totalCount.get();
    }

    /**
     * 取消从 startIndex 开始的未开始 Future。
     * 已在运行的 Future 不取消(cancel(false) 对 running 线程是 no-op),
     * 其临时文件由 cleanupTempDir 清理。
     */
    private void cancelUnstarted(List<Future<?>> futures, int startIndex,
                                  List<String> partitions, String tag) {
        for (int i = startIndex; i < futures.size(); i++) {
            Future<?> f = futures.get(i);
            if (!f.isDone()) {
                if (f.cancel(false)) {
                    log.warn("Parallel[{}] partition {} cancelled due to prior failure", tag, partitions.get(i));
                }
            }
        }
    }

    /** 统计仍存在的临时文件数(有内容,未被拼接删除) */
    private static int countCompleted(List<Path> tempFiles) {
        int count = 0;
        for (Path p : tempFiles) {
            if (Files.exists(p) && Files.size(p) > 0) {
                count++;
            }
        }
        return count;
    }

    private void cleanupTempDir(Path tempDir) {
        if (tempDir == null) return;
        try {
            if (Files.exists(tempDir)) {
                try (var paths = Files.walk(tempDir)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to clean temp dir {}: {}", tempDir, e.getMessage());
        }
    }
}
