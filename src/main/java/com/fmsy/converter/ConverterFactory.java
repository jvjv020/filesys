package com.fmsy.converter;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 转换器工厂类 - 根据parserType获取对应的FileConverter实例
 *
 * 设计模式：工厂模式 + 策略模式
 * - Spring自动注入所有FileConverter实现（DBF/XML/CSV/TXT）
 * - 初始化时将转换器注册到Map中
 * - get()方法根据parserType快速查找对应的转换器
 */
@Component
@RequiredArgsConstructor
public class ConverterFactory {

    private final List<FileConverter> converters;
    private Map<String, FileConverter> converterMap;

    /**
     * 初始化转换器映射表
     * 将所有FileConverter按格式类型注册到Map中
     */
    @PostConstruct
    public void init() {
        converterMap = converters.stream()
                .collect(Collectors.toMap(FileConverter::getFormat, Function.identity()));
    }

    /**
     * 获取转换器实例
     * @param parserType 解析器类型（DBF/XML/CSV/TXT）
     * @return 对应的转换器实现
     * @throws IllegalArgumentException 不支持的类型
     */
    public FileConverter get(String parserType) {
        FileConverter converter = converterMap.get(parserType);
        if (converter == null) {
            throw new IllegalArgumentException("Unsupported parser type: " + parserType);
        }
        return converter;
    }
}
