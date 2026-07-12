package com.fmsy.model;

import com.fmsy.enums.EmptyDataHandling;
import com.fmsy.enums.TransferScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TransferConfig Tests")
class TransferConfigTest {

    @Nested
    @DisplayName("field getter/setter")
    class FieldTests {

        @Test
        @DisplayName("should store and retrieve categoryCode")
        void shouldStoreAndRetrieveCategoryCode() {
            TransferConfig config = new TransferConfig();
            config.setCategoryCode("CAT001");
            assertEquals("CAT001", config.getCategoryCode());
        }

        @Test
        @DisplayName("should store and retrieve controlCode")
        void shouldStoreAndRetrieveControlCode() {
            TransferConfig config = new TransferConfig();
            config.setControlCode("CTRL001");
            assertEquals("CTRL001", config.getControlCode());
        }

        @Test
        @DisplayName("should store and retrieve scenario")
        void shouldStoreAndRetrieveScenario() {
            TransferConfig config = new TransferConfig();
            config.setScenario(TransferScenario.UPLOAD_SINGLE);
            assertEquals(TransferScenario.UPLOAD_SINGLE, config.getScenario());
        }

        @Test
        @DisplayName("should store and retrieve dbName")
        void shouldStoreAndRetrieveDbName() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_PRIMARY");
            assertEquals("DB_PRIMARY", config.getDbName());
        }

        @Test
        @DisplayName("should store and retrieve tableName")
        void shouldStoreAndRetrieveTableName() {
            TransferConfig config = new TransferConfig();
            config.setTableName("employee");
            assertEquals("employee", config.getTableName());
        }

        @Test
        @DisplayName("should store and retrieve ftpName")
        void shouldStoreAndRetrieveFtpName() {
            TransferConfig config = new TransferConfig();
            config.setFtpName("ftpServer");
            assertEquals("ftpServer", config.getFtpName());
        }

        @Test
        @DisplayName("should store and retrieve filePath")
        void shouldStoreAndRetrieveFilePath() {
            TransferConfig config = new TransferConfig();
            config.setFilePath("/data/files/{YYYYMMDD}.csv");
            assertEquals("/data/files/{YYYYMMDD}.csv", config.getFilePath());
        }

        @Test
        @DisplayName("should store and retrieve clearTableFlag")
        void shouldStoreAndRetrieveClearTableFlag() {
            TransferConfig config = new TransferConfig();
            config.setClearTableFlag("Y");
            assertEquals("Y", config.getClearTableFlag());
        }

        @Test
        @DisplayName("should store and retrieve overwriteFlag")
        void shouldStoreAndRetrieveOverwriteFlag() {
            TransferConfig config = new TransferConfig();
            config.setOverwriteFlag("Y");
            assertEquals("Y", config.getOverwriteFlag());
        }

        @Test
        @DisplayName("should store and retrieve concurrency")
        void shouldStoreAndRetrieveConcurrency() {
            TransferConfig config = new TransferConfig();
            config.setConcurrency(5);
            assertEquals(5, config.getConcurrency());
        }

        @Test
        @DisplayName("should store and retrieve serialFlag")
        void shouldStoreAndRetrieveSerialFlag() {
            TransferConfig config = new TransferConfig();
            config.setSerialFlag("Y");
            assertEquals("Y", config.getSerialFlag());
        }

        @Test
        @DisplayName("should store and retrieve nodeId")
        void shouldStoreAndRetrieveNodeId() {
            TransferConfig config = new TransferConfig();
            config.setNodeId("node-1");
            assertEquals("node-1", config.getNodeId());
        }

        @Test
        @DisplayName("should store and retrieve parserType")
        void shouldStoreAndRetrieveParserType() {
            TransferConfig config = new TransferConfig();
            config.setParserType("CSV");
            assertEquals("CSV", config.getParserType());
        }

        @Test
        @DisplayName("should store and retrieve parserConfig")
        void shouldStoreAndRetrieveParserConfig() {
            TransferConfig config = new TransferConfig();
            config.setParserConfig("{\"encoding\":\"UTF-8\"}");
            assertEquals("{\"encoding\":\"UTF-8\"}", config.getParserConfig());
        }

        @Test
        @DisplayName("should store and retrieve preOperations")
        void shouldStoreAndRetrievePreOperations() {
            TransferConfig config = new TransferConfig();
            config.setPreOperations("READY:/data/file.txt");
            assertEquals("READY:/data/file.txt", config.getPreOperations());
        }

        @Test
        @DisplayName("should store and retrieve postOperations")
        void shouldStoreAndRetrievePostOperations() {
            TransferConfig config = new TransferConfig();
            config.setPostOperations("FB:{X}.fbk;L");
            assertEquals("FB:{X}.fbk;L", config.getPostOperations());
        }

        @Test
        @DisplayName("should store and retrieve ignoreFields")
        void shouldStoreAndRetrieveIgnoreFields() {
            TransferConfig config = new TransferConfig();
            config.setIgnoreFields("FIELD1,FIELD2");
            assertEquals("FIELD1,FIELD2", config.getIgnoreFields());
        }

        @Test
        @DisplayName("should store and retrieve splitFields")
        void shouldStoreAndRetrieveSplitFields() {
            TransferConfig config = new TransferConfig();
            config.setSplitFields("REGION,STATUS");
            assertEquals("REGION,STATUS", config.getSplitFields());
        }

        @Test
        @DisplayName("should store and retrieve emptyDataHandling")
        void shouldStoreAndRetrieveEmptyDataHandling() {
            TransferConfig config = new TransferConfig();
            config.setEmptyDataHandling(EmptyDataHandling.SKIP);
            assertEquals(EmptyDataHandling.SKIP, config.getEmptyDataHandling());
        }
    }

    @Nested
    @DisplayName("configuration object construction")
    class ConfigurationConstructionTests {

        @Test
        @DisplayName("should build complete upload config")
        void shouldBuildCompleteUploadConfig() {
            TransferConfig config = new TransferConfig();
            config.setCategoryCode("EMP");
            config.setControlCode("UPL");
            config.setScenario(TransferScenario.UPLOAD_SINGLE);
            config.setDbName("DB_PRIMARY");
            config.setTableName("employee");
            config.setFtpName("ftp1");
            config.setFilePath("/upload/{YYYYMMDD}.dbf");
            config.setParserType("DBF");
            config.setPreOperations("READY:{name}");
            config.setPostOperations("FB:{stem}.fbk;L S M");

            assertEquals("EMP", config.getCategoryCode());
            assertEquals("UPL", config.getControlCode());
            assertEquals(TransferScenario.UPLOAD_SINGLE, config.getScenario());
            assertEquals("DB_PRIMARY", config.getDbName());
            assertEquals("employee", config.getTableName());
            assertEquals("ftp1", config.getFtpName());
            assertEquals("/upload/{YYYYMMDD}.dbf", config.getFilePath());
            assertEquals("DBF", config.getParserType());
        }

        @Test
        @DisplayName("should build complete download config with split")
        void shouldBuildCompleteDownloadConfigWithSplit() {
            TransferConfig config = new TransferConfig();
            config.setCategoryCode("RPT");
            config.setControlCode("DL");
            config.setScenario(TransferScenario.DOWNLOAD_MULTI_NODE);
            config.setDbName("DB_REPORT");
            config.setTableName("report_data");
            config.setFtpName("ftp2");
            config.setFilePath("/download/reports/{YYYYMMDD}/{FIELD_REGION}.csv");
            config.setParserType("CSV");
            config.setSplitFields("REGION");
            config.setEmptyDataHandling(EmptyDataHandling.ERROR);

            assertEquals("RPT", config.getCategoryCode());
            assertEquals("DL", config.getControlCode());
            assertEquals(TransferScenario.DOWNLOAD_MULTI_NODE, config.getScenario());
            assertEquals("REGION", config.getSplitFields());
            assertEquals(EmptyDataHandling.ERROR, config.getEmptyDataHandling());
        }
    }
}
