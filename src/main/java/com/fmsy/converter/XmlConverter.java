package com.fmsy.converter;

import com.fmsy.model.FieldMapping;
import com.fmsy.util.SystemConstants;
import lombok.extern.slf4j.Slf4j;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * XML文件转换器
 *
 * 功能说明：
 * - 使用StAX（Streaming API for XML）解析
 * - 生成XML格式输出
 * - 流式解析避免内存溢出
 *
 * XML格式：
 * <data>
 *   <record>
 *     <field1>value1</field1>
 *     <field2>value2</field2>
 *   </record>
 * </data>
 *
 * 解析配置参数(parserConfig JSON)：
 * - encoding: 文件编码,默认 UTF-8
 * - rootElement: 根元素名,默认 "data"
 * - recordElement: 记录元素名,默认 "record"
 * - fieldAsAttribute: 是否将字段作为属性输出,默认 false
 */
@Slf4j
public class XmlConverter implements FileConverter {

    /** 安全 XMLInputFactory — 禁用外部实体和 DTD，防止 XXE 攻击 */
    private static XMLInputFactory createSecureInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return factory;
    }

    /** 安全 TransformerFactory — 禁用外部实体 */
    private static TransformerFactory createSecureTransformerFactory() {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        return factory;
    }

    /** 安全 SchemaFactory — 禁用外部实体 */
    private static SchemaFactory createSecureSchemaFactory() {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (SAXException ignored) {
        }
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (SAXException ignored) {
        }
        return factory;
    }

    @Override
    public Iterator<List<Map<String, Object>>> parse(InputStream input, FieldMapping mapping) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        XmlExternalConfigResolver.XmlProfile profile = XmlExternalConfigResolver.INSTANCE.resolve(mapping, cfg);
        if (profile.useParseXsl()) {
            byte[] transformed = transformInput(input, profile);
            return new XmlIterator(new ByteArrayInputStream(transformed), profile.rootElement(),
                    profile.recordElement(), profile.fieldPaths());
        }
        if (profile.xsdPath() != null) {
            log.warn("XML stream mode skips XSD validation for {}", profile.xsdPath());
        }
        return new XmlIterator(input, profile.rootElement(), profile.recordElement(), profile.fieldPaths());
    }

    @Override
    public void generate(OutputStream output, Iterator<List<Map<String, Object>>> data, FieldMapping mapping) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        XmlExternalConfigResolver.XmlProfile profile = XmlExternalConfigResolver.INSTANCE.resolve(mapping, cfg);
        if (profile.useGenerateXsl()) {
            ByteArrayOutputStream intermediate = new ByteArrayOutputStream();
            writeXml(intermediate, data, mapping, profile);
            byte[] transformed = transformBytes(intermediate.toByteArray(), profile.generateXslPath(), profile.xsdPath());
            try {
                output.write(transformed);
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to write transformed XML", e);
            }
            return;
        }
        // 普通路径:使用三段式写入
        writeHeader(output, mapping, 0);
        writeDataRecords(output, data, mapping);
        writeFooter(output, mapping);
    }

    @Override
    public void writeHeader(OutputStream output, FieldMapping mapping, long recordCount) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        XmlExternalConfigResolver.XmlProfile profile = XmlExternalConfigResolver.INSTANCE.resolve(mapping, cfg);
        try {
            XMLStreamWriter writer = XMLOutputFactory.newInstance().createXMLStreamWriter(output, profile.encoding());
            writer.writeStartDocument(profile.encoding(), "1.0");
            writer.writeCharacters("\n");
            writer.writeStartElement(profile.rootElement());
            writer.writeCharacters("\n");
            writer.flush();
        } catch (XMLStreamException e) {
            throw new RuntimeException("Failed to write XML header", e);
        }
    }

    @Override
    public int writeDataRecords(OutputStream output, Iterator<List<Map<String, Object>>> data, FieldMapping mapping) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        XmlExternalConfigResolver.XmlProfile profile = XmlExternalConfigResolver.INSTANCE.resolve(mapping, cfg);
        List<String> fields = mapping.getTableFields();
        int recordCount = 0;
        try {
            XMLStreamWriter writer = XMLOutputFactory.newInstance().createXMLStreamWriter(output, profile.encoding());
            while (data.hasNext()) {
                List<Map<String, Object>> batch = data.next();
                for (Map<String, Object> row : batch) {
                    writer.writeCharacters("  ");
                    writer.writeStartElement(profile.recordElement());
                    if (profile.fieldAsAttribute()) {
                        for (String field : fields) {
                            Object value = mapping.getValue(row, field);
                            writer.writeAttribute(xmlName(field, profile), value != null ? value.toString() : "");
                        }
                    } else {
                        for (String field : fields) {
                            Object value = mapping.getValue(row, field);
                            writeField(writer, xmlPath(field, profile), value);
                        }
                        writer.writeCharacters("\n  ");
                    }
                    writer.writeEndElement();
                    writer.writeCharacters("\n");
                    recordCount++;
                }
            }
            writer.flush();
            log.info("Generated XML with {} records", recordCount);
        } catch (XMLStreamException e) {
            throw new RuntimeException("Failed to write XML records", e);
        }
        return recordCount;
    }

    @Override
    public void writeFooter(OutputStream output, FieldMapping mapping) {
        Map<String, String> cfg = ConverterUtils.mergeConfig(getDefaultConfig(), mapping);
        XmlExternalConfigResolver.XmlProfile profile = XmlExternalConfigResolver.INSTANCE.resolve(mapping, cfg);
        try {
            // 注意:writeHeader/writeDataRecords 已各自创建并关闭了 XMLStreamWriter,
            // 此处不能再创建新 writer(无对应 startElement 状态),直接写原始关闭标签。
            output.write(("</" + profile.rootElement() + ">\n").getBytes(profile.encoding()));
            output.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write XML footer", e);
        }
    }

    private void writeXml(OutputStream output, Iterator<List<Map<String, Object>>> data,
                          FieldMapping mapping, XmlExternalConfigResolver.XmlProfile profile) {
        XMLStreamWriter writer = null;
        try {
            writer = XMLOutputFactory.newInstance().createXMLStreamWriter(output, profile.encoding());
            writer.writeStartDocument(profile.encoding(), "1.0");
            writer.writeCharacters("\n");
            writer.writeStartElement(profile.rootElement());
            writer.writeCharacters("\n");

            List<String> fields = mapping.getTableFields();
            int recordCount = 0;

            while (data.hasNext()) {
                List<Map<String, Object>> batch = data.next();
                for (Map<String, Object> row : batch) {
                    writer.writeCharacters("  ");
                    writer.writeStartElement(profile.recordElement());
                    if (profile.fieldAsAttribute()) {
                        for (String field : fields) {
                            Object value = mapping.getValue(row, field);
                            writer.writeAttribute(xmlName(field, profile), value != null ? value.toString() : "");
                        }
                    } else {
                        for (String field : fields) {
                            Object value = mapping.getValue(row, field);
                            writeField(writer, xmlPath(field, profile), value);
                        }
                        writer.writeCharacters("\n  ");
                    }
                    writer.writeEndElement();
                    writer.writeCharacters("\n");
                    recordCount++;
                }
            }

            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
            log.info("Generated XML with {} records", recordCount);
        } catch (XMLStreamException e) {
            throw new RuntimeException("Failed to generate XML", e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (XMLStreamException e) {
                    log.warn("Failed to close XML writer: {}", e.getMessage());
                }
            }
        }
    }

    private static String xmlPath(String field, XmlExternalConfigResolver.XmlProfile profile) {
        return profile.fieldPaths().getOrDefault(field, field);
    }

    private static String xmlName(String field, XmlExternalConfigResolver.XmlProfile profile) {
        String path = xmlPath(field, profile);
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static void writeField(XMLStreamWriter writer, String path, Object value) throws XMLStreamException {
        writer.writeCharacters("\n    ");
        String[] elements = path.split("/");
        for (String element : elements) {
            writer.writeStartElement(element);
        }
        if (value != null) {
            writer.writeCharacters(value.toString());
        }
        for (int i = elements.length - 1; i >= 0; i--) {
            writer.writeEndElement();
        }
    }

    private static byte[] transformInput(InputStream input, XmlExternalConfigResolver.XmlProfile profile) {
        try {
            byte[] source = input.readAllBytes();
            if (profile.xsdPath() != null) {
                validate(source, profile.xsdPath());
            }
            return transformBytes(source, profile.parseXslPath(), null);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read XML input", e);
        }
    }

    private static byte[] transformBytes(byte[] source, java.nio.file.Path xslPath, java.nio.file.Path xsdPath) {
        try {
            Transformer transformer = createSecureTransformerFactory().newTransformer(new StreamSource(xslPath.toFile()));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new StreamSource(new ByteArrayInputStream(source)), new StreamResult(output));
            byte[] transformed = output.toByteArray();
            if (xsdPath != null) {
                validate(transformed, xsdPath);
            }
            return transformed;
        } catch (TransformerException e) {
            throw new RuntimeException("Failed to transform XML with XSL: " + xslPath, e);
        }
    }

    private static void validate(byte[] source, java.nio.file.Path xsdPath) {
        try {
            SchemaFactory factory = createSecureSchemaFactory();
            Schema schema = factory.newSchema(xsdPath.toFile());
            Validator validator = schema.newValidator();
            validator.validate(new StreamSource(new ByteArrayInputStream(source)));
        } catch (SAXException | java.io.IOException e) {
            throw new RuntimeException("XML validation failed: " + xsdPath, e);
        }
    }

    @Override
    public String getFormat() {
        return "XML";
    }

    @Override
    public Map<String, String> getDefaultConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("encoding", "UTF-8");
        config.put("rootElement", "data");
        config.put("recordElement", "record");
        config.put("fieldAsAttribute", "false");
        return config;
    }

    /**
     * XML流式迭代器
     * 使用StAX流式读取XML，每次返回一批记录
     */
    private static class XmlIterator implements Iterator<List<Map<String, Object>>>, AutoCloseable {
        private final XMLStreamReader reader;
        private final String rootElement;
        private final String recordElement;
        private final Map<String, String> fieldPaths;
        private boolean hasNext = true;
        private boolean positionedAtRecord;
        private boolean closed = false;
        private static final int BATCH_SIZE = SystemConstants.DEFAULT_BATCH_SIZE;

        XmlIterator(InputStream input, String rootElement, String recordElement, Map<String, String> fieldPaths) {
            this.rootElement = rootElement;
            this.recordElement = recordElement;
            this.fieldPaths = fieldPaths != null ? fieldPaths : Map.of();
            XMLStreamReader xmlReader = null;
            try {
                XMLInputFactory factory = createSecureInputFactory();
                xmlReader = factory.createXMLStreamReader(input);
                reader = xmlReader;
                parseRootAndFields();
            } catch (XMLStreamException e) {
                if (xmlReader != null) {
                    try {
                        xmlReader.close();
                    } catch (XMLStreamException ignored) {
                    }
                }
                throw new RuntimeException("Failed to parse XML", e);
            }
        }

        @Override
        public void close() {
            if (!closed) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                }
                closed = true;
            }
        }

        /** 跳过根元素，定位到第一个record */
        private void parseRootAndFields() throws XMLStreamException {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    String elementName = reader.getLocalName();
                    if (elementName.equals(recordElement)) {
                        positionedAtRecord = true;
                        return;
                    }
                }
            }
            hasNext = false;
            close();
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        /** 返回一批记录 */
        @Override
        public List<Map<String, Object>> next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            List<Map<String, Object>> batch = new ArrayList<>();

            try {
                int count = 0;
                while (hasNext && count < BATCH_SIZE) {
                    if (!positionedAtRecord && !advanceToNextRecord()) {
                        break;
                    }
                    batch.add(readRecord());
                    positionedAtRecord = false;
                    count++;
                }
                if (batch.isEmpty()) {
                    throw new NoSuchElementException();
                }
            } catch (XMLStreamException e) {
                close();
                throw new RuntimeException("Failed to parse XML", e);
            }
            return batch;
        }

        private boolean advanceToNextRecord() throws XMLStreamException {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamReader.START_ELEMENT && recordElement.equals(reader.getLocalName())) {
                    positionedAtRecord = true;
                    return true;
                }
                if (event == XMLStreamReader.END_ELEMENT && rootElement.equals(reader.getLocalName())) {
                    hasNext = false;
                    close();
                    return false;
                }
            }
            hasNext = false;
            close();
            return false;
        }

        private Map<String, Object> readRecord() throws XMLStreamException {
            return fieldPaths.isEmpty() ? readFlatRecord() : readMappedRecord();
        }

        private Map<String, Object> readFlatRecord() throws XMLStreamException {
            Map<String, Object> record = new HashMap<>();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    record.put(reader.getLocalName(), reader.getElementText());
                } else if (event == XMLStreamReader.END_ELEMENT && recordElement.equals(reader.getLocalName())) {
                    return record;
                }
            }
            hasNext = false;
            close();
            return record;
        }

        private Map<String, Object> readMappedRecord() throws XMLStreamException {
            Map<String, Object> record = new HashMap<>();
            List<String> path = new ArrayList<>();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    String name = reader.getLocalName();
                    path.add(name);
                    String field = fieldFor(String.join("/", path));
                    if (field != null) {
                        record.put(field, reader.getElementText());
                        path.remove(path.size() - 1);
                    }
                } else if (event == XMLStreamReader.END_ELEMENT) {
                    if (recordElement.equals(reader.getLocalName())) {
                        return record;
                    }
                    if (!path.isEmpty()) {
                        path.remove(path.size() - 1);
                    }
                }
            }
            hasNext = false;
            close();
            return record;
        }

        private String fieldFor(String path) {
            for (Map.Entry<String, String> entry : fieldPaths.entrySet()) {
                if (entry.getValue().equals(path)) {
                    return entry.getKey();
                }
            }
            return null;
        }
    }
}