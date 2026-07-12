package com.fmsy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AppConfig Tests")
class AppConfigTest {

    @Nested
    @DisplayName("default values")
    class DefaultValuesTests {

        private final AppConfig config = new AppConfig();

        @Test
        @DisplayName("should have default polling interval of 10")
        void pollingIntervalDefault() {
            assertEquals(10, config.getPolling().getInterval());
        }

        @Test
        @DisplayName("should have default polling batchSize of 20")
        void pollingBatchSizeDefault() {
            assertEquals(20, config.getPolling().getBatchSize());
        }

        @Test
        @DisplayName("should have default polling taskTimeoutHours of 1")
        void pollingTaskTimeoutHoursDefault() {
            assertEquals(1, config.getPolling().getTaskTimeoutHours());
        }

        @Test
        @DisplayName("should have default download bucketBatchSize of 3")
        void downloadBucketBatchSizeDefault() {
            assertEquals(3, config.getDownload().getBucketBatchSize());
        }

        @Test
        @DisplayName("should have default download maxPollIterations of 1000")
        void downloadMaxPollIterationsDefault() {
            assertEquals(1000, config.getDownload().getMaxPollIterations());
        }

        @Test
        @DisplayName("should have default download parallelThreads of 3")
        void downloadParallelThreadsDefault() {
            assertEquals(3, config.getDownload().getParallelThreads());
        }

        @Test
        @DisplayName("should have null node id by default")
        void nodeIdDefault() {
            assertNull(config.getNode().getId());
        }
    }

    @Nested
    @DisplayName("getNodeId convenience method")
    class GetNodeIdTests {

        @Test
        @DisplayName("should return null when node id is not set")
        void shouldReturnNullWhenNotSet() {
            AppConfig config = new AppConfig();
            assertNull(config.getNodeId());
        }

        @Test
        @DisplayName("should return node id when set")
        void shouldReturnNodeIdWhenSet() {
            AppConfig config = new AppConfig();
            config.getNode().setId("NODE_01");
            assertEquals("NODE_01", config.getNodeId());
        }

        @Test
        @DisplayName("should return updated node id after change")
        void shouldReturnUpdatedNodeId() {
            AppConfig config = new AppConfig();
            config.getNode().setId("NODE_A");
            config.getNode().setId("NODE_B");
            assertEquals("NODE_B", config.getNodeId());
        }
    }

    @Nested
    @DisplayName("custom configuration via setters")
    class CustomConfigurationTests {

        @Test
        @DisplayName("should update polling interval")
        void shouldUpdatePollingInterval() {
            AppConfig config = new AppConfig();
            config.getPolling().setInterval(30);
            assertEquals(30, config.getPolling().getInterval());
        }

        @Test
        @DisplayName("should update polling batchSize")
        void shouldUpdatePollingBatchSize() {
            AppConfig config = new AppConfig();
            config.getPolling().setBatchSize(50);
            assertEquals(50, config.getPolling().getBatchSize());
        }

        @Test
        @DisplayName("should update download bucketBatchSize")
        void shouldUpdateDownloadBucketBatchSize() {
            AppConfig config = new AppConfig();
            config.getDownload().setBucketBatchSize(10);
            assertEquals(10, config.getDownload().getBucketBatchSize());
        }

        @Test
        @DisplayName("should update download maxPollIterations")
        void shouldUpdateDownloadMaxPollIterations() {
            AppConfig config = new AppConfig();
            config.getDownload().setMaxPollIterations(500);
            assertEquals(500, config.getDownload().getMaxPollIterations());
        }

        @Test
        @DisplayName("should update download parallelThreads")
        void shouldUpdateDownloadParallelThreads() {
            AppConfig config = new AppConfig();
            config.getDownload().setParallelThreads(5);
            assertEquals(5, config.getDownload().getParallelThreads());
        }
    }

    @Nested
    @DisplayName("node configuration")
    class NodeConfigurationTests {

        @Test
        @DisplayName("should set and get node id")
        void shouldSetAndGetNodeId() {
            AppConfig.Node node = new AppConfig.Node();
            node.setId("production-node-1");
            assertEquals("production-node-1", node.getId());
        }

        @Test
        @DisplayName("should allow null node id")
        void shouldAllowNullNodeId() {
            AppConfig.Node node = new AppConfig.Node();
            node.setId(null);
            assertNull(node.getId());
        }
    }

    @Nested
    @DisplayName("nested config objects")
    class NestedConfigTests {

        @Test
        @DisplayName("node should not be null after construction")
        void nodeShouldNotBeNull() {
            AppConfig config = new AppConfig();
            assertNotNull(config.getNode());
        }

        @Test
        @DisplayName("polling should not be null after construction")
        void pollingShouldNotBeNull() {
            AppConfig config = new AppConfig();
            assertNotNull(config.getPolling());
        }

        @Test
        @DisplayName("download should not be null after construction")
        void downloadShouldNotBeNull() {
            AppConfig config = new AppConfig();
            assertNotNull(config.getDownload());
        }
    }
}
