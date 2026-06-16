package com.fmsy.model;

import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.enums.TransferScenario;
import lombok.Data;

/**
 * 传输配置实体类 - 代表传输配置表的一条记录
 *
 * 配置说明：
 * - categoryCode + controlCode: 组成配置的唯一标识
 * - scenario: 传输场景（UPLOAD_SINGLE/UPLOAD_MULTI/DOWNLOAD_SINGLE等）
 * - dbName/tableName: 数据库连接名和目标表名
 * - ftpName/filePath: FTP连接名和文件路径
 * - parserType: 解析器类型（DBF/XML/CSV/TXT）
 * - preOperations/postOperations: 前置/后置文件操作
 * - splitFields: 拆分字段，用于多节点分桶
 * - emptyDataHandling: 空数据处理方式（ERROR/SKIP/ALLOW/SEND_EMPTY）
 *
 * 占位符语法：
 * - {YYYYMMDD}, {YYYYMMDDHHmmss}: 日期时间占位符
 * - {FIELD_NAME}: 字段值占位符（需配合splitFields使用）
 * - {EXTRA_INFO}: 额外信息占位符
 * - {stem}/{name}/{ext}/{dir}/{dn}/{up}: 文件衍生变量（后置操作路径继承）
 */
@Data
public class TransferConfig {
    private String categoryCode;
    private String controlCode;
    private TransferScenario scenario;
    private String dbName;
    private String tableName;
    private String ftpName;
    private String filePath;
    private String clearTableFlag;
    private String overwriteFlag;
    private Integer concurrency;
    private String serialFlag;
    private String nodeId;
    private String parserType;
    private String parserConfig;
    private String preOperations;
    private String postOperations;
    private String ignoreFields;
    private String splitFields;
    private EmptyDataHandling emptyDataHandling;
}