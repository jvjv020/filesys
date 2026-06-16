package com.fmsy.converter;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 文件转换器接口 - 所有文件格式转换器的统一接口
 *
 * 设计模式：策略模式
 * - 每种文件格式（DBF/XML/CSV/TXT）都有自己的转换器实现
 * - 通过ConverterFactory工厂类根据parserType获取对应转换器
 *
 * 方法说明：
 * - parse: 从InputStream解析文件内容，返回迭代器（支持流式处理大数据文件）
 * - generate: 将数据写入OutputStream，生成目标格式文件
 * - getFormat: 返回转换器类型标识（DBF/XML/CSV/TXT）
 * - getDefaultConfig: 返回默认配置参数
 *
 * 三段式写入（用于并行文件生成）：
 * - writeHeader: 写文件头（CSV列名行、XML根元素起始、DBF二进制头）
 * - writeDataRecords: 仅写数据记录（不含头尾），供并行模式写入临时文件
 * - writeFooter: 写文件尾（XML根元素结束、DBF EOF标记）
 * - generate 内部已改为调用三段式，保证串行模式行为一致
 */
public interface FileConverter {

    /**
     * 解析文件流
     * @param input 输入流（FTP文件下载后的内容）
     * @param mapping 字段映射配置
     * @return 迭代器，每个元素是一批记录列表（用于流式处理）
     */
    Iterator<List<Map<String, Object>>> parse(InputStream input, FieldMapping mapping);

    /**
     * 生成文件流（内部调用 writeHeader + writeDataRecords + writeFooter）
     * @param output 输出流（写入FTP目标位置）
     * @param data 数据迭代器
     * @param mapping 字段映射配置
     */
    void generate(OutputStream output, Iterator<List<Map<String, Object>>> data, FieldMapping mapping);

    /**
     * 获取转换器格式类型
     * @return 格式类型字符串（DBF/XML/CSV/TXT）
     */
    String getFormat();

    /**
     * 获取默认配置
     * @return 默认配置键值对
     */
    Map<String, String> getDefaultConfig();

    /**
     * 写文件头。
     * CSV: 可选的列名行; TXT: 列名行; XML: {@code <?xml?> + <root>}; DBF: 二进制头(含字段描述符)
     *
     * @param output      输出流
     * @param mapping     字段映射
     * @param recordCount 总记录数（仅 DBF 需要用于填写文件头中的记录数字段; 其他格式忽略）
     */
    default void writeHeader(OutputStream output, FieldMapping mapping, long recordCount) {
        // 默认无操作 — 仅 DBF 需要实现
    }

    /**
     * 仅写数据记录（不含文件头和尾），供并行模式写入临时文件。
     *
     * @param output  输出流
     * @param data    数据迭代器
     * @param mapping 字段映射
     * @return 写入的记录数
     */
    int writeDataRecords(OutputStream output, Iterator<List<Map<String, Object>>> data, FieldMapping mapping);

    /**
     * 写文件尾。
     * XML: {@code </root>}; DBF: EOF 标记 {@code 0x1A}; CSV/TXT: 无操作
     *
     * @param output  输出流
     * @param mapping 字段映射
     */
    default void writeFooter(OutputStream output, FieldMapping mapping) {
        // 默认无操作 — 仅 XML/DBF 需要实现
    }

    /**
     * 统计输入流中的记录行数。
     *
     * <p>各格式实现方式:
     * <ul>
     *   <li>CSV/TXT: 逐行读取,跳过表头行,统计数据行数</li>
     *   <li>DBF: 从文件头偏移 4-7 字节读取 LE int32 记录数(无需遍历全文件)</li>
     *   <li>XML: 返回 -1(需要完整解析才能准确统计,代价过高)</li>
     * </ul>
     *
     * @param input   输入流(调用方负责关闭)
     * @param mapping 字段映射(包含 parserConfig 配置)
     * @return 记录行数;难以计算时返回 -1
     */
    default int countRecords(InputStream input, FieldMapping mapping) {
        return -1;
    }
}