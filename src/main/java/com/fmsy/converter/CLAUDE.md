# converter 模块 — 文件格式转换器

## 职责
在 FTP 文件和数据库之间做格式转换，支持 DBF/XML/CSV/TXT 四种格式的流式解析与生成。

## 关键类

| 类 | 角色 |
|---|------|
| `FileConverter` | 转换器接口（策略模式），定义 `parse` / `generate` / `writeHeader` / `writeDataRecords` / `writeFooter` / `countRecords` |
| `ConverterFactory` | 工厂类，按 `parserType` 获取对应 `FileConverter` 实例 |
| `CloseableIterator<T>` | 可关闭迭代器封装，迭代时自动累加 `recordCount` |
| `CsvConverter` | CSV 转换器（默认 UTF-8，逗号分隔，支持 header/skipLines/overrideHeaders） |
| `DbfConverter` | DBF 转换器（默认 GBK，支持 C/N/D/L 字段类型，二进制二进制格式） |
| `XmlConverter` | XML 转换器（StAX 流式解析，支持 XSL 转换、XSD 校验） |
| `TxtConverter` | TXT 转换器（默认 UTF-8，"|" 分隔，支持 delimiter/fixed 两种模式） |
| `ConverterUtils` | 包私有工具方法（config 合并、charset 解析、JSON 数组解析） |
| `XmlExternalConfigResolver` | XML 外部配置解析（fields.json / XSD / XSL 路径解析） |

## 三段式写入协议
所有 `generate` 方法内部改为调用三段式：
1. `writeHeader(output, mapping, recordCount)` — 写文件头
2. `writeDataRecords(output, data, mapping)` — 写数据记录
3. `writeFooter(output, mapping)` — 写文件尾

`ParallelFileGenerator` 利用此协议实现分区并行：header 在主线程写，各分区并行写临时文件（仅数据段），主线程顺序拼接。

## 设计约束
- **必须保持流式处理**：`parse` 返回 `Iterator<List<Map>>`，不允许 `List` 收集到内存
- 配置通过 `ConverterUtils.mergeConfig(defaultConfig, mapping)` 合并 parserConfig 覆盖值
- 所有转换器无 Spring 注解，通过 `ConverterFactory` 自动收集
