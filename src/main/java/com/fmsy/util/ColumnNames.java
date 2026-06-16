package com.fmsy.util;

public final class ColumnNames {

    private ColumnNames() {
    }

    // 命令表
    public static final String ID = "自增列";
    public static final String CATEGORY_CODE = "类别代号";
    public static final String CONTROL_CODE = "控制代号";
    public static final String COMMAND_TYPE = "指令类型";
    public static final String AUDIT_COUNT = "稽核数";
    public static final String EXTRA_INFO = "额外信息";
    public static final String PROCESSING_NODE = "处理节点";
    public static final String PROCESS_STATUS = "处理状态";
    public static final String PROCESS_START_TIME = "处理起始时间";
    public static final String PROCESS_END_TIME = "指令处理结束时间";

    // 明细表
    public static final String DETAIL_ID = "自增列";
    public static final String DETAIL_COMMAND_ID = "对应指令ID";
    public static final String FIELD_NAME = "指定字段名称";
    public static final String FIELD_VALUE = "指定字段取值";
    public static final String FILE_NAME = "指定文件名";

    // 结果表
    public static final String COMMAND_ID = "指令ID";
    public static final String FTP_NAME = "FTP名称";
    public static final String DB_INFO = "数据库信息";
    public static final String FILE_PATH = "文件路径";
    public static final String TRANSFER_DATE = "传输日期";
    public static final String RESULT = "处理结果";
    public static final String DURATION_MS = "处理耗时ms";
    public static final String RECORD_COUNT = "数据记录数量";
    public static final String FILE_SIZE = "文件大小";
    public static final String RESULT_DESC = "结果说明";
    public static final String TRANSFER_DIRECTION = "传输方向";

    // 传输配置表
    public static final String SCENARIO = "传输场景";
    public static final String DB_NAME = "数据库名称";
    public static final String TABLE_NAME = "数据库表名";
    public static final String PARSER_TYPE = "解析器类型";
    public static final String PRE_OPS = "前置文件操作";
    public static final String POST_OPS = "后置文件操作";
    public static final String SPLIT_FIELDS = "拆分字段配置";
    public static final String TEMP_CONFIG = "temp_config";
    public static final String STATUS = "状态";

    // 状态值
    public static final String STATUS_EMPTY = "";
    public static final String STATUS_PROCESSING = "P";
    public static final String STATUS_SUCCESS = "Y";
    public static final String STATUS_SKIPPED = "N";
    public static final String STATUS_ERROR = "E";
    public static final String STATUS_VALID = "有效";

    // 默认值
    public static final String DEFAULT_DB = "DB_DEFAULT";
}