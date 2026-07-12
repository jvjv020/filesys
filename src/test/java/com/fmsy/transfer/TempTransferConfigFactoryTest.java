package com.fmsy.transfer;

import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.enums.TransferScenario;
import com.fmsy.exception.TransferException;
import com.fmsy.model.Command;
import com.fmsy.model.TransferConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TempTransferConfigFactory Tests")
class TempTransferConfigFactoryTest {

    private TempTransferConfigFactory factory;

    @BeforeEach
    void setUp() {
        factory = new TempTransferConfigFactory();
    }

    @Nested
    @DisplayName("build - error cases")
    class BuildErrorCases {

        @Test
        @DisplayName("should throw when temp_config is null")
        void shouldThrowWhenTempConfigIsNull() {
            Command command = new Command();
            command.setId(1L);
            command.setTempConfig(null);

            TransferException ex = assertThrows(TransferException.class,
                    () -> factory.build(command));
            assertEquals("TEMP_MISSING_FIELD", ex.getErrorCode());
        }

        @Test
        @DisplayName("should throw when temp_config is empty")
        void shouldThrowWhenTempConfigIsEmpty() {
            Command command = new Command();
            command.setId(1L);
            command.setTempConfig("   ");

            TransferException ex = assertThrows(TransferException.class,
                    () -> factory.build(command));
            assertEquals("TEMP_MISSING_FIELD", ex.getErrorCode());
        }

        @Test
        @DisplayName("should throw when required field is missing")
        void shouldThrowWhenRequiredFieldMissing() {
            Command command = new Command();
            command.setId(1L);
            command.setTempConfig("{\"ftpName\":\"ftp1\"}");

            TransferException ex = assertThrows(TransferException.class,
                    () -> factory.build(command));
            assertTrue(ex.getMessage().contains("scenario"));
        }

        @Test
        @DisplayName("should throw when scenario enum value is invalid")
        void shouldThrowWhenScenarioInvalid() {
            Command command = new Command();
            command.setId(1L);
            command.setTempConfig("{" +
                    "\"scenario\":\"INVALID_SCENARIO\"," +
                    "\"dbName\":\"DB1\"," +
                    "\"tableName\":\"mytable\"," +
                    "\"ftpName\":\"ftp1\"," +
                    "\"filePath\":\"/path\"," +
                    "\"parserType\":\"CSV\"" +
                    "}");

            TransferException ex = assertThrows(TransferException.class,
                    () -> factory.build(command));
            assertEquals("TEMP_INVALID_ENUM", ex.getErrorCode());
        }

        @Test
        @DisplayName("should throw when JSON parsing returns empty map")
        void shouldThrowWhenJsonParseReturnsEmpty() {
            Command command = new Command();
            command.setId(1L);
            command.setTempConfig("{}");

            TransferException ex = assertThrows(TransferException.class,
                    () -> factory.build(command));
            assertEquals("TEMP_PARSE_FAILED", ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("build - successful")
    class BuildSuccessful {

        @Test
        @DisplayName("should build config from valid JSON")
        void shouldBuildConfigFromValidJson() {
            Command command = new Command();
            command.setId(1L);
            command.setCategoryCode("TEMP_CAT");
            command.setControlCode("TEMP_CTRL");
            command.setTempConfig("{" +
                    "\"scenario\":\"UPLOAD_SINGLE\"," +
                    "\"dbName\":\"DB1\"," +
                    "\"tableName\":\"mytable\"," +
                    "\"ftpName\":\"ftp1\"," +
                    "\"filePath\":\"/data/upload.csv\"," +
                    "\"parserType\":\"CSV\"" +
                    "}");

            TransferConfig config = factory.build(command);

            assertNotNull(config);
            assertEquals("TEMP_CAT", config.getCategoryCode());
            assertEquals("TEMP_CTRL", config.getControlCode());
            assertEquals(TransferScenario.UPLOAD_SINGLE, config.getScenario());
            assertEquals("DB1", config.getDbName());
            assertEquals("mytable", config.getTableName());
            assertEquals("ftp1", config.getFtpName());
            assertEquals("/data/upload.csv", config.getFilePath());
            assertEquals("CSV", config.getParserType());
        }

        @Test
        @DisplayName("should set defaults for optional fields")
        void shouldSetDefaultsForOptionalFields() {
            Command command = new Command();
            command.setId(2L);
            command.setTempConfig("{" +
                    "\"scenario\":\"DOWNLOAD_SINGLE\"," +
                    "\"dbName\":\"DB1\"," +
                    "\"tableName\":\"mytable\"," +
                    "\"ftpName\":\"ftp1\"," +
                    "\"filePath\":\"/data/download.csv\"," +
                    "\"parserType\":\"DBF\"" +
                    "}");

            TransferConfig config = factory.build(command);

            assertEquals("N", config.getClearTableFlag());
            assertEquals("Y", config.getOverwriteFlag());
            assertEquals("N", config.getSerialFlag());
            assertEquals(EmptyDataHandling.ALLOW, config.getEmptyDataHandling());
            assertNull(config.getNodeId());
            assertNull(config.getParserConfig());
            assertNull(config.getSplitFields());
        }

        @Test
        @DisplayName("should parse optional integer fields")
        void shouldParseOptionalIntegerFields() {
            Command command = new Command();
            command.setId(3L);
            command.setTempConfig("{" +
                    "\"scenario\":\"DOWNLOAD_MULTI_NODE\"," +
                    "\"dbName\":\"DB1\"," +
                    "\"tableName\":\"mytable\"," +
                    "\"ftpName\":\"ftp1\"," +
                    "\"filePath\":\"/data/multi.csv\"," +
                    "\"parserType\":\"CSV\"," +
                    "\"concurrency\":\"5\"," +
                    "\"emptyDataHandling\":\"ERROR\"," +
                    "\"splitFields\":\"REGION\"," +
                    "\"nodeId\":\"node-2\"" +
                    "}");

            TransferConfig config = factory.build(command);

            assertEquals(Integer.valueOf(5), config.getConcurrency());
            assertEquals(EmptyDataHandling.ERROR, config.getEmptyDataHandling());
            assertEquals("REGION", config.getSplitFields());
            assertEquals("node-2", config.getNodeId());
        }

        @Test
        @DisplayName("should handle invalid concurrency value gracefully")
        void shouldHandleInvalidConcurrencyGracefully() {
            Command command = new Command();
            command.setId(4L);
            command.setTempConfig("{" +
                    "\"scenario\":\"UPLOAD_SINGLE\"," +
                    "\"dbName\":\"DB1\"," +
                    "\"tableName\":\"mytable\"," +
                    "\"ftpName\":\"ftp1\"," +
                    "\"filePath\":\"/path\"," +
                    "\"parserType\":\"CSV\"," +
                    "\"concurrency\":\"not-a-number\"" +
                    "}");

            TransferConfig config = factory.build(command);

            assertNull(config.getConcurrency());
        }

        @Test
        @DisplayName("should handle invalid emptyDataHandling value gracefully")
        void shouldHandleInvalidEmptyDataHandlingGracefully() {
            Command command = new Command();
            command.setId(5L);
            command.setTempConfig("{" +
                    "\"scenario\":\"UPLOAD_SINGLE\"," +
                    "\"dbName\":\"DB1\"," +
                    "\"tableName\":\"mytable\"," +
                    "\"ftpName\":\"ftp1\"," +
                    "\"filePath\":\"/path\"," +
                    "\"parserType\":\"CSV\"," +
                    "\"emptyDataHandling\":\"INVALID\"" +
                    "}");

            TransferConfig config = factory.build(command);

            assertEquals(EmptyDataHandling.ALLOW, config.getEmptyDataHandling());
        }

        @Test
        @DisplayName("should set all optional fields")
        void shouldSetAllOptionalFields() {
            Command command = new Command();
            command.setId(6L);
            command.setTempConfig("{" +
                    "\"scenario\":\"UPLOAD_MULTI\"," +
                    "\"dbName\":\"DB1\"," +
                    "\"tableName\":\"mytable\"," +
                    "\"ftpName\":\"ftp1\"," +
                    "\"filePath\":\"/path\"," +
                    "\"parserType\":\"XML\"," +
                    "\"clearTableFlag\":\"Y\"," +
                    "\"overwriteFlag\":\"N\"," +
                    "\"serialFlag\":\"Y\"," +
                    "\"nodeId\":\"node-3\"," +
                    "\"parserConfig\":\"{\\\"encoding\\\":\\\"GBK\\\"}\"," +
                    "\"preOperations\":\"READY:/flag.txt\"," +
                    "\"postOperations\":\"SUB:/sub.flg;L\"," +
                    "\"ignoreFields\":\"col1,col2\"," +
                    "\"splitFields\":\"REGION,STATUS\"" +
                    "}");

            TransferConfig config = factory.build(command);

            assertEquals("Y", config.getClearTableFlag());
            assertEquals("N", config.getOverwriteFlag());
            assertEquals("Y", config.getSerialFlag());
            assertEquals("node-3", config.getNodeId());
            assertNotNull(config.getParserConfig());
            assertEquals("READY:/flag.txt", config.getPreOperations());
            assertEquals("SUB:/sub.flg;L", config.getPostOperations());
            assertEquals("col1,col2", config.getIgnoreFields());
            assertEquals("REGION,STATUS", config.getSplitFields());
        }
    }
}
