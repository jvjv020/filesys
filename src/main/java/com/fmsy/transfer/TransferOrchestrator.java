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

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

/**
 * 传输编排器 — 按 (scenario, commandType) 显式路由到对应 Handler。
 *
 * <p>
 * 合并自原 UploadOrchestrator + DownloadOrchestrator，路由方式从 supports() 遍历
 * 改为显式 switch，避免标记接口开销。加新场景只需在本类 dispatch 加一行 case 即可。
 *
 * <p>
 * COORDINATED(S) 类型的 DOWNLOAD_MULTI_NODE 命令不由本类处理，
 * 而是由 TransferService 直接转给 ChildBucketProcessor。
 */
@Service
public class TransferOrchestrator extends AbstractTransferOrchestrator {

    private final SingleUploadHandler singleUpload;
    private final MultiUploadHandler multiUpload;
    private final SingleDownloadHandler singleDownload;
    private final SingleNodeDownloadHandler singleNodeDownload;
    private final MultiNodeDownloadHandler multiNodeDownload;

    public TransferOrchestrator(SingleUploadHandler singleUpload,
            MultiUploadHandler multiUpload,
            SingleDownloadHandler singleDownload,
            SingleNodeDownloadHandler singleNodeDownload,
            MultiNodeDownloadHandler multiNodeDownload,
            CommandRepository commandRepository,
            ResultRepository resultRepository,
            DataSourceConfig.DbPool dbPool) {
        super(commandRepository, resultRepository, dbPool);
        this.singleUpload = singleUpload;
        this.multiUpload = multiUpload;
        this.singleDownload = singleDownload;
        this.singleNodeDownload = singleNodeDownload;
        this.multiNodeDownload = multiNodeDownload;
    }

    @Override
    protected void dispatch(Command command, TransferConfig config, Result result) throws Exception {
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
}
