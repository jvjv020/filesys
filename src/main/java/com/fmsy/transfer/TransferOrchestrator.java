package com.fmsy.transfer;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.TransferScenario;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.transfer.download.MultiNodeDownloadHandler;
import com.fmsy.transfer.download.SingleDownloadHandler;
import com.fmsy.transfer.download.SingleNodeDownloadHandler;
import com.fmsy.transfer.upload.MultiUploadHandler;
import com.fmsy.transfer.upload.SingleUploadHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 传输编排器 — 按 scenario + commandType 显式路由到对应 Handler。
 *
 * <p>包含 execute 模板方法（try-dispatch → catch → finalize），
 * 统一处理异常兜底和指令表+结果表写入。
 *
 * <p>COORDINATED(S) 类型的 DOWNLOAD_MULTI_NODE 命令不由本类处理，
 * 由 TransferService 直接转给 ChildBucketProcessor。
 */
@Slf4j
@Service
public class TransferOrchestrator {

    private final SingleUploadHandler singleUpload;
    private final MultiUploadHandler multiUpload;
    private final SingleDownloadHandler singleDownload;
    private final SingleNodeDownloadHandler singleNodeDownload;
    private final MultiNodeDownloadHandler multiNodeDownload;
    private final CommandRepository commandRepository;
    private final ResultRepository resultRepository;
    private final DataSourceConfig.DbPool dbPool;

    public TransferOrchestrator(SingleUploadHandler singleUpload,
            MultiUploadHandler multiUpload,
            SingleDownloadHandler singleDownload,
            SingleNodeDownloadHandler singleNodeDownload,
            MultiNodeDownloadHandler multiNodeDownload,
            CommandRepository commandRepository,
            ResultRepository resultRepository,
            DataSourceConfig.DbPool dbPool) {
        this.singleUpload = singleUpload;
        this.multiUpload = multiUpload;
        this.singleDownload = singleDownload;
        this.singleNodeDownload = singleNodeDownload;
        this.multiNodeDownload = multiNodeDownload;
        this.commandRepository = commandRepository;
        this.resultRepository = resultRepository;
        this.dbPool = dbPool;
    }

    /**
     * 执行模板：try { dispatch } catch → result.failWith(e) → finally → finalize。
     */
    public final void execute(Command command, TransferConfig config) {
        log.info("Executing {} for command: {}", config.getScenario(), command.getId());
        Result result = newResult(config.getScenario());
        try {
            dispatch(command, config, result);
        } catch (Exception e) {
            log.error("{} execution failed for command {}: {}", config.getScenario(), command.getId(), e.getMessage(), e);
            result.failWith(e);
        } finally {
            finalize(command, config, result);
        }
    }

    /**
     * 按 scenario + commandType 派发到对应 Handler。
     */
    private void dispatch(Command command, TransferConfig config, Result result) throws Exception {
        TransferScenario scenario = config.getScenario();
        CommandType commandType = command.getCommandType();

        switch (scenario) {
            case UPLOAD_SINGLE:
                singleUpload.handle(command, config, result);
                break;
            case UPLOAD_MULTI:
                // BATCH（明细表指定文件）由 SingleUploadHandler 处理，其余走 MultiUploadHandler
                if (command.getCommandType() == CommandType.BATCH) {
                    singleUpload.handle(command, config, result);
                } else {
                    multiUpload.handle(command, config, result);
                }
                break;
            case DOWNLOAD_SINGLE:
                singleDownload.handle(command, config, result);
                break;
            case DOWNLOAD_SINGLE_NODE:
                singleNodeDownload.handle(command, config, result);
                break;
            case DOWNLOAD_MULTI_NODE:
                multiNodeDownload.handle(command, config, result);
                break;
            default:
                throw new IllegalArgumentException("No handler for scenario=" + scenario
                        + ", commandType=" + commandType);
        }
    }

    /**
     * 收尾：组装 Command/Config 派生字段 → 写指令表 → 写结果表。
     *
     * <p>多节点下传场景走正常落库（状态为 P），合并完成回调会覆盖终态为 Y 或 E。
     * UPDATE 指令表 + INSERT 结果表 包在事务中：避免"指令已置终态但结果表未写"的状态。
     *
     * <p>包级可见，供测试直接验证 finalize 逻辑。
     */
    void finalize(Command command, TransferConfig config, Result result) {
        result.markEnd(command, config);
        dbPool.getTransactionTemplate(config.getDbName()).execute(status -> {
            commandRepository.updateStatus(command.getId(), result.getResult());
            resultRepository.insert(result);
            return null;
        });
    }

    /**
     * 根据场景方向创建 Result 实例并标记开始时间。
     */
    private Result newResult(TransferScenario scenario) {
        String direction = scenario.name().startsWith("UPLOAD")
                ? Result.DIRECTION_UPLOAD : Result.DIRECTION_DOWNLOAD;
        return Result.builder()
                .transferDirection(direction)
                .markStart()
                .build();
    }
}