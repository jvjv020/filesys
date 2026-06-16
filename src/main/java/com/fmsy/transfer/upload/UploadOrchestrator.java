package com.fmsy.transfer.upload;

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
 * 上传编排器 — 按 (scenario, commandType) 遍历 {@link UploadHandler} 列表派发。
 *
 * <p>execute / finalize / newResult 已抽到 {@link AbstractTransferOrchestrator} 基类;
 * 本类只保留 dispatch,本身不再"知道"具体有哪些 Handler — 加新场景只需新建
 * {@code @Component} Handler 并实现 {@link UploadHandler} 接口,Spring 自动按类型注入。
 *
 * <p>Spring 注入 {@code List<UploadHandler>}:自动只拿 Upload 方向(本包)Handler,
 * 不会混入 Download 方向(其他包)Handler,语义清晰。
 */
@Service
public class UploadOrchestrator extends AbstractTransferOrchestrator {

    private final List<UploadHandler> handlers;

    public UploadOrchestrator(List<UploadHandler> handlers,
                              CommandRepository commandRepository,
                              ResultRepository resultRepository,
                              ChildCommandMonitor childCommandMonitor,
                              DataSourceConfig.DbPool dbPool) {
        super(commandRepository, resultRepository, childCommandMonitor, dbPool, Result.DIRECTION_UPLOAD);
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
        throw new IllegalArgumentException("No upload handler for scenario=" + scenario
                + ", commandType=" + commandType);
    }
}
