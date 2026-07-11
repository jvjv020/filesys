# exception 模块 — 异常定义

## 职责
定义 FMSY 统一的异常体系。

## 关键类

### `TransferException`
- 继承 `RuntimeException`
- 含 `errorCode` 字段便于程序化处理
- 错误码：`TRANSFER_ERROR`（默认）/ `CONFIG_NOT_FOUND` / `FTP_ACTION_FAILED` / `FTP_CONNECTION_FAILED` / `FTP_GET_INTERRUPTED` / `TEMP_MISSING_FIELD` / `TEMP_PARSE_FAILED` / `TEMP_INVALID_ENUM` / `POST_AUDIT_FAILED`

## 设计原则
- 单一 `RuntimeException` 已足够，不分业务子异常
- 业务方一般在边界 catch 后写结果表置 ERROR 状态
- 不依赖异常类型做分流处理
