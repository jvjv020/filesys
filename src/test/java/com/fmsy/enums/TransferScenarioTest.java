package com.fmsy.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TransferScenario Tests")
class TransferScenarioTest {

    @Nested
    @DisplayName("enum values")
    class EnumValuesTests {

        @Test
        @DisplayName("should have exactly 5 enum values")
        void shouldHaveExactlyFiveEnumValues() {
            assertEquals(5, TransferScenario.values().length);
        }

        @Test
        @DisplayName("all enum values should be non-null")
        void allEnumValuesShouldBeNonNull() {
            for (TransferScenario scenario : TransferScenario.values()) {
                assertNotNull(scenario);
            }
        }
    }

    @Nested
    @DisplayName("isUpload() method")
    class IsUploadTests {

        @ParameterizedTest
        @EnumSource(value = TransferScenario.class, names = {"UPLOAD_SINGLE", "UPLOAD_MULTI"})
        @DisplayName("should return true for upload scenarios")
        void shouldReturnTrueForUploadScenarios(TransferScenario scenario) {
            assertTrue(scenario.isUpload());
        }

        @ParameterizedTest
        @EnumSource(value = TransferScenario.class, names = {"DOWNLOAD_SINGLE", "DOWNLOAD_SINGLE_NODE", "DOWNLOAD_MULTI_NODE"})
        @DisplayName("should return false for download scenarios")
        void shouldReturnFalseForDownloadScenarios(TransferScenario scenario) {
            assertFalse(scenario.isUpload());
        }

        @Test
        @DisplayName("UPLOAD_SINGLE should be upload")
        void uploadSingleShouldBeUpload() {
            assertTrue(TransferScenario.UPLOAD_SINGLE.isUpload());
        }

        @Test
        @DisplayName("UPLOAD_MULTI should be upload")
        void uploadMultiShouldBeUpload() {
            assertTrue(TransferScenario.UPLOAD_MULTI.isUpload());
        }

        @Test
        @DisplayName("DOWNLOAD_SINGLE should not be upload")
        void downloadSingleShouldNotBeUpload() {
            assertFalse(TransferScenario.DOWNLOAD_SINGLE.isUpload());
        }

        @Test
        @DisplayName("DOWNLOAD_SINGLE_NODE should not be upload")
        void downloadSingleNodeShouldNotBeUpload() {
            assertFalse(TransferScenario.DOWNLOAD_SINGLE_NODE.isUpload());
        }

        @Test
        @DisplayName("DOWNLOAD_MULTI_NODE should not be upload")
        void downloadMultiNodeShouldNotBeUpload() {
            assertFalse(TransferScenario.DOWNLOAD_MULTI_NODE.isUpload());
        }
    }

    @Nested
    @DisplayName("enum name consistency")
    class EnumNameConsistencyTests {

        @Test
        @DisplayName("enum names should match expected pattern")
        void enumNamesShouldMatchExpectedPattern() {
            assertEquals("UPLOAD_SINGLE", TransferScenario.UPLOAD_SINGLE.name());
            assertEquals("UPLOAD_MULTI", TransferScenario.UPLOAD_MULTI.name());
            assertEquals("DOWNLOAD_SINGLE", TransferScenario.DOWNLOAD_SINGLE.name());
            assertEquals("DOWNLOAD_SINGLE_NODE", TransferScenario.DOWNLOAD_SINGLE_NODE.name());
            assertEquals("DOWNLOAD_MULTI_NODE", TransferScenario.DOWNLOAD_MULTI_NODE.name());
        }
    }
}
