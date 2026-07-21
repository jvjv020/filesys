package com.fmsy.transfer.download;

import com.fmsy.util.ColumnNames;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 桶处理聚合结果 — 线程安全。
 *
 * <p>使用 {@link AtomicInteger} 和 {@link AtomicBoolean} 实现无锁并发聚合，
 * {@link ConcurrentLinkedQueue} 收集生成的文件路径供后稽核回滚使用。
 * 各桶的 {@link DownloadSupport.PipelineResult} 通过 {@link #accumulate} 合并到此对象。
 *
 * <p>与上传 Handler 的"单文件失败不影响其他文件"风格一致：
 * 单个桶的失败仅记录到计数器，不影响其他桶的并行处理。
 *
 * <p>原本是 {@link SingleNodeDownloadHandler} 的内部类，提取为独立类后
 * 可供同一包内其他 Handler 复用。
 */
public class BucketBatchResult {
    private final AtomicInteger totalRecordCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger skippedCount = new AtomicInteger(0);
    private final AtomicBoolean allSuccess = new AtomicBoolean(true);
    private final ConcurrentLinkedQueue<String> generatedFiles = new ConcurrentLinkedQueue<>();

    /**
     * 聚合一个桶的处理结果 — 线程安全，可被多个桶的线程并发调用。
     *
     * @param pr       管线执行结果
     * @param filePath 生成的目标文件路径（仅成功时记录，用于后稽核回滚）
     */
    public void accumulate(DownloadSupport.PipelineResult pr, String filePath) {
        if (pr.success()) {
            totalRecordCount.addAndGet(pr.recordCount());
            successCount.incrementAndGet();
            if (filePath != null) {
                generatedFiles.add(filePath);
            }
        } else {
            allSuccess.set(false);
            if (ColumnNames.STATUS_SKIPPED.equals(pr.status())) {
                skippedCount.incrementAndGet();
            } else {
                failedCount.incrementAndGet();
            }
        }
    }

    /**
     * 记录桶处理异常 — 当处理过程捕获到 Exception 时调用。
     * 标记全部失败计数器 +1，但 {@code generatedFiles} 不变（异常时尚未生成文件）。
     */
    public void accumulateFailure() {
        allSuccess.set(false);
        failedCount.incrementAndGet();
    }

    /** 强制标记为失败（聚合后稽核失败时由调用方调用） */
    public void forceFailure() {
        allSuccess.set(false);
        failedCount.incrementAndGet();
    }

    /** 根据聚合结果判定最终状态：全成功→Y，有失败→E，仅跳过→N */
    public String determineStatus() {
        if (allSuccess.get()) return ColumnNames.STATUS_SUCCESS;
        if (failedCount.get() > 0) return ColumnNames.STATUS_ERROR;
        if (skippedCount.get() > 0) return ColumnNames.STATUS_SKIPPED;
        return ColumnNames.STATUS_ERROR;
    }

    public int getTotalRecordCount() { return totalRecordCount.get(); }
    public int getSuccessCount() { return successCount.get(); }
    public int getFailedCount() { return failedCount.get(); }
    public int getSkippedCount() { return skippedCount.get(); }
    public boolean isAllSuccess() { return allSuccess.get(); }
    public List<String> getGeneratedFiles() {
        return List.copyOf(generatedFiles);
    }
}