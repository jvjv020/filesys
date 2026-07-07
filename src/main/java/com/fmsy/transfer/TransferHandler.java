package com.fmsy.transfer;

import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;

/**
 * 传输场景 Handler 抽象 — 6 个具体 Handler 实现此接口。
 *
 * <p>路由不再通过 supports() 遍历，而是由 {@link TransferOrchestrator}
 * 按 scenario + commandType 显式 switch 路由。
 */
public interface TransferHandler {
    void handle(Command command, TransferConfig config, Result result) throws Exception;
}
