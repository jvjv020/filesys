package com.fmsy.transfer;

import com.fmsy.enums.CommandType;
import com.fmsy.enums.TransferScenario;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;

/**
 * 传输场景 Handler 抽象 — 5 个场景 × 3 种命令类型的可插拔派发点。
 *
 * <p>每个 Handler 自声明它负责的 (scenario, commandType) 组合,
 * 上层 {@code UploadOrchestrator} / {@code DownloadOrchestrator} 通过
 * {@code List<TransferHandler>} 注入 + {@link #supports} 路由,
 * 加新场景只需新建 Handler 并加 {@code @Component},Orchestrator 零改动。
 *
 * <p>典型实现约定:
 * <ul>
 *   <li>{@link #supports(TransferScenario, CommandType)} 必须"互斥且完备":
 *       同一方向内两个 Handler 不能同时返回 true,每个有效组合至少有 1 个 Handler 返回 true</li>
 *   <li>{@link #handle} 抛 Exception;Orchestrator 统一 catch + result.failWith + finalize</li>
 *   <li>Handler 内部产生的所有"早返"通过 {@link Result#setOutcome} 累积状态,
 *       不应抛出非受检异常(走 failWith 会丢上下文)</li>
 * </ul>
 *
 * <p>当前 Handler → (scenario, commandType) 映射:
 * <pre>
 *   SingleUploadHandler        UPLOAD_SINGLE         (SERIAL or null)
 *   MultiDirectoryUploadHandler UPLOAD_MULTI         (SERIAL or null)
 *   MultiBatchUploadHandler     UPLOAD_MULTI         BATCH
 *   SingleDownloadHandler      DOWNLOAD_SINGLE       (SERIAL or null)
 *   SingleNodeDownloadHandler  DOWNLOAD_SINGLE_NODE  (any except COORDINATED)
 *   MultiNodeDownloadHandler   DOWNLOAD_MULTI_NODE   (any except COORDINATED)
 * </pre>
 */
public interface TransferHandler {

    /**
     * 当前 Handler 是否处理给定的 (scenario, commandType) 组合。
     *
     * <p>实现注意:
     * <ul>
     *   <li>{@code commandType} 可能为 {@code null} (对应 {@link CommandType#SERIAL} — 序列化为 null 入库)</li>
     *   <li>DOWNLOAD_SINGLE_NODE / DOWNLOAD_MULTI_NODE 不接受 {@link CommandType#COORDINATED},
     *       因为 S 型子命令由 {@code TransferService} 直接转给 {@code DetailPollingService}</li>
     *   <li>调用方应假设"互斥":遍历时第一个返回 true 的 Handler 即被选中</li>
     * </ul>
     */
    boolean supports(TransferScenario scenario, CommandType commandType);

    /**
     * 执行本场景的传输逻辑。Orchestrator 统一 catch + 写结果表。
     *
     * @param command 主命令实体(包含 commandType / extraInfo / auditCount)
     * @param config  传输配置(包含 scenario / 路径 / 解析器 / splitFields 等)
     * @param result  共享结果对象,Handler 通过 setOutcome / markChildrenCreated / markChildrenFailed / failWith 累积状态
     */
    void handle(Command command, TransferConfig config, Result result) throws Exception;
}
