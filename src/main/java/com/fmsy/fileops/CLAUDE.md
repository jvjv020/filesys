# fileops 模块 — 文件操作服务

## 职责
处理前置/后置文件操作（标志文件、消息发送）。

## 关键类

| 类 | 角色 |
|---|------|
| `FlagFileService` | 标志文件处理服务，短关键字语法体系 |
| `MessageSender` | 消息发送服务（LOG / WEBHOOK 通道） |

## 前置操作语法
- `READY:path` — 检查文件存在
- `FLAG:path` — 检查标志存在
- `FLAG:path;mode` — 标志内容 vs 数据文件计算值
- `FLAG:path;expect;mode` — 显式期望值 vs 数据文件计算值

## 后置操作语法
- `FB:path;content` — 反馈文件
- `SUB:path;content` — 子标志文件
- `TOTAL:path;content` — 总标志文件
- `DEL:path` — 删除匹配文件
- `REN:from;to` — 重命名
- `MSG:target;body` — 发送消息

## 路径继承
不以 `/` 开头的 pattern 自动加 `{dir}/` 前缀（同目录）。支持 `..` 父目录遍历。

## 模式码
`L`(行数) `S`(字节数) `M`(MD5) `C`(处理记录数) `N`(时间戳) `D`(日期) `T`(时间) `F`(文件名) `X`(stem) `E`(扩展名) `P`(完整路径)

单字母模式码独立出现时替换，多字母大写词（`SUCCESS` / `OK` / `ERROR`）保持原样。

## MessageSender 通道
- `LOG:loggerName` — SLF4J INFO 日志
- `WEBHOOK:url` — HTTP POST JSON，含指数退避重试（3 次）
- `MAIL` / `SMS` / `MQ` — 未实现，仅 warn 占位
