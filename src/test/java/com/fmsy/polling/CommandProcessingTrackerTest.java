package com.fmsy.polling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CommandProcessingTracker Tests")
class CommandProcessingTrackerTest {

    private CommandProcessingTracker createTracker() {
        return new CommandProcessingTracker();
    }

    @Nested
    @DisplayName("addNode and hasNode")
    class NodeManagementTests {

        @Test
        @DisplayName("should add node and detect it with hasNode")
        void shouldAddAndDetectNode() {
            CommandProcessingTracker tracker = createTracker();

            tracker.addNode("node1");

            assertTrue(tracker.hasNode("node1"));
        }

        @Test
        @DisplayName("should return false for non-existent node")
        void shouldReturnFalseForNonExistentNode() {
            CommandProcessingTracker tracker = createTracker();

            assertFalse(tracker.hasNode("node1"));
        }

        @Test
        @DisplayName("should handle multiple nodes")
        void shouldHandleMultipleNodes() {
            CommandProcessingTracker tracker = createTracker();

            tracker.addNode("node1");
            tracker.addNode("node2");
            tracker.addNode("node3");

            assertTrue(tracker.hasNode("node1"));
            assertTrue(tracker.hasNode("node2"));
            assertTrue(tracker.hasNode("node3"));
            assertFalse(tracker.hasNode("node4"));
        }

        @Test
        @DisplayName("should overwrite node with same id (idempotent)")
        void shouldOverwriteNodeIdempotent() {
            CommandProcessingTracker tracker = createTracker();

            tracker.addNode("node1");
            tracker.addNode("node1");

            assertTrue(tracker.hasNode("node1"));
        }
    }

    @Nested
    @DisplayName("recordMainId and hasMainId")
    class MainIdTrackingTests {

        @Test
        @DisplayName("should record and detect main id for node")
        void shouldRecordAndDetectMainId() {
            CommandProcessingTracker tracker = createTracker();

            tracker.recordMainId("node1", "main123");

            assertTrue(tracker.hasMainId("node1", "main123"));
        }

        @Test
        @DisplayName("should return false for non-recorded main id")
        void shouldReturnFalseForNonRecordedMainId() {
            CommandProcessingTracker tracker = createTracker();

            tracker.recordMainId("node1", "main123");

            assertFalse(tracker.hasMainId("node1", "main456"));
            assertFalse(tracker.hasMainId("node2", "main123"));
        }

        @Test
        @DisplayName("should handle null node id gracefully")
        void shouldHandleNullNodeId() {
            CommandProcessingTracker tracker = createTracker();

            tracker.recordMainId(null, "main123");

            assertFalse(tracker.hasMainId(null, "main123"));
        }

        @Test
        @DisplayName("should handle null main id gracefully")
        void shouldHandleNullMainId() {
            CommandProcessingTracker tracker = createTracker();

            tracker.recordMainId("node1", null);

            assertFalse(tracker.hasMainId("node1", null));
        }

        @Test
        @DisplayName("should handle both null parameters")
        void shouldHandleBothNullParams() {
            CommandProcessingTracker tracker = createTracker();

            tracker.recordMainId(null, null);

            assertFalse(tracker.hasMainId(null, null));
        }

        @Test
        @DisplayName("should track multiple main ids for same node")
        void shouldTrackMultipleMainIdsForSameNode() {
            CommandProcessingTracker tracker = createTracker();

            tracker.recordMainId("node1", "main1");
            tracker.recordMainId("node1", "main2");
            tracker.recordMainId("node1", "main3");

            assertTrue(tracker.hasMainId("node1", "main1"));
            assertTrue(tracker.hasMainId("node1", "main2"));
            assertTrue(tracker.hasMainId("node1", "main3"));
        }

        @Test
        @DisplayName("should track main ids across different nodes")
        void shouldTrackMainIdsAcrossNodes() {
            CommandProcessingTracker tracker = createTracker();

            tracker.recordMainId("node1", "main1");
            tracker.recordMainId("node2", "main2");
            tracker.recordMainId("node3", "main3");

            assertTrue(tracker.hasMainId("node1", "main1"));
            assertFalse(tracker.hasMainId("node1", "main2"));
            assertTrue(tracker.hasMainId("node2", "main2"));
            assertTrue(tracker.hasMainId("node3", "main3"));
        }
    }

    @Nested
    @DisplayName("hasSType flag")
    class HasSTypeTests {

        @Test
        @DisplayName("should default hasSType to false")
        void shouldDefaultHasSTypeToFalse() {
            CommandProcessingTracker tracker = createTracker();

            assertFalse(tracker.isHasSType());
        }

        @Test
        @DisplayName("should allow setting hasSType to true")
        void shouldAllowSettingHasSTypeTrue() {
            CommandProcessingTracker tracker = createTracker();

            tracker.setHasSType(true);

            assertTrue(tracker.isHasSType());
        }

        @Test
        @DisplayName("should allow toggling hasSType")
        void shouldAllowTogglingHasSType() {
            CommandProcessingTracker tracker = createTracker();

            tracker.setHasSType(true);
            assertTrue(tracker.isHasSType());
            tracker.setHasSType(false);
            assertFalse(tracker.isHasSType());
        }
    }

    @Nested
    @DisplayName("mainCommandId field")
    class MainCommandIdTests {

        @Test
        @DisplayName("should default mainCommandId to null")
        void shouldDefaultMainCommandIdToNull() {
            CommandProcessingTracker tracker = createTracker();

            assertNull(tracker.getMainCommandId());
        }

        @Test
        @DisplayName("should allow setting mainCommandId")
        void shouldAllowSettingMainCommandId() {
            CommandProcessingTracker tracker = createTracker();

            tracker.setMainCommandId("main123");

            assertEquals("main123", tracker.getMainCommandId());
        }
    }

    @Nested
    @DisplayName("nodes map")
    class NodesMapTests {

        @Test
        @DisplayName("should have empty nodes map by default")
        void shouldHaveEmptyNodesMapByDefault() {
            CommandProcessingTracker tracker = createTracker();

            assertTrue(tracker.getNodes().isEmpty());
        }

        @Test
        @DisplayName("should reflect added nodes in map")
        void shouldReflectAddedNodesInMap() {
            CommandProcessingTracker tracker = createTracker();

            tracker.addNode("node1");
            tracker.addNode("node2");

            assertEquals(2, tracker.getNodes().size());
            assertTrue(tracker.getNodes().containsKey("node1"));
            assertTrue(tracker.getNodes().containsKey("node2"));
        }
    }

    @Nested
    @DisplayName("nodeMainIds map")
    class NodeMainIdsMapTests {

        @Test
        @DisplayName("should have empty nodeMainIds map by default")
        void shouldHaveEmptyNodeMainIdsMapByDefault() {
            CommandProcessingTracker tracker = createTracker();

            assertTrue(tracker.getNodeMainIds().isEmpty());
        }

        @Test
        @DisplayName("should reflect recorded main ids in map")
        void shouldReflectRecordedMainIdsInMap() {
            CommandProcessingTracker tracker = createTracker();

            tracker.recordMainId("node1", "main1");
            tracker.recordMainId("node1", "main2");
            tracker.recordMainId("node2", "main3");

            assertEquals(2, tracker.getNodeMainIds().size());
            Set<String> node1Mains = tracker.getNodeMainIds().get("node1");
            assertNotNull(node1Mains);
            assertEquals(2, node1Mains.size());
            assertTrue(node1Mains.contains("main1"));
            assertTrue(node1Mains.contains("main2"));
        }
    }
}
