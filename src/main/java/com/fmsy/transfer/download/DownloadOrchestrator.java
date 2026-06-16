package com.fmsy.transfer.download;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.enums.CommandType;
import com.fmsy.enums.TransferScenario;
import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.ResultRepository;
import com.fmsy.transfer.AbstractTransferOrchestrator;
import com.fmsy.transfer.TransferHandler;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 下载编排器 — 按 (scenario, commandType) 遍历 {@link DownloadHandler} 列表派发。
 *
 * <p>execute / finalize / newResult 已抽到 {@link AbstractTransferOrchestrator} 基类;
 * 本类只保留 dispatch,本身不再"知道"具体有哪些 Handler — 加新场景只需新建
 * {@code @Component} Handler 并实现 {@link DownloadHandler} 接口,Spring 自动按类型注入。
 *
 * <p>注意:COORDINATED(S) 类型的 DOWNLOAD_MULTI_NODE 命令不由本类处理,
 * 而是由 {@code TransferService} 直接转给 {@code DetailPollingService}。
 * 各 Handler 在 {@link TransferHandler#supports} 中显式拒绝 COORDINATED。
 */
@Service
public class DownloadOrchestrator extends AbstractTransferOrchestrator {

    private final List<DownloadHandler> handlers;

    public DownloadOrchestrator(List<DownloadHandler> handlers,
                                CommandRepository commandRepository,
                                ResultRepository resultRepository,
                                ChildCommandMonitor childCommandMonitor,
                                DataSourceConfig.DbPool dbPool) {
        super(commandRepository, resultRepository, childCommandMonitor, dbPool, Result.DIRECTION_DOWNLOAD);
        this.handlers = handlers;
    }

    @Override
    protected void dispatch(Command command, TransferConfig config, Result result) throws Exception {
        TransferScenario scenario = config.getScenario();
        CommandType commandType = command.getCommandType();
        for (TransferHandler handler : handlers) {
            if (handler.supports(scenario, commandType)) {
                handler.handle(command, config, result);
                return;
            }
        }
        throw new IllegalArgumentException("No download handler for scenario=" + scenario
                + ", commandType=" + commandType);
    }
}
