package com.fmsy.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EmptyDataHandling Tests")
class EmptyDataHandlingTest {

    @Nested
    @DisplayName("enum values")
    class EnumValuesTests {

        @Test
        @DisplayName("should have exactly 4 enum constants")
        void shouldHaveFourConstants() {
            assertEquals(4, EmptyDataHandling.values().length);
        }

        @Test
        @DisplayName("should contain ERROR")
        void shouldContainError() {
            assertNotNull(EmptyDataHandling.valueOf("ERROR"));
            assertEquals(EmptyDataHandling.ERROR, EmptyDataHandling.valueOf("ERROR"));
        }

        @Test
        @DisplayName("should contain ALLOW")
        void shouldContainAllow() {
            assertNotNull(EmptyDataHandling.valueOf("ALLOW"));
            assertEquals(EmptyDataHandling.ALLOW, EmptyDataHandling.valueOf("ALLOW"));
        }

        @Test
        @DisplayName("should contain SEND_EMPTY")
        void shouldContainSendEmpty() {
            assertNotNull(EmptyDataHandling.valueOf("SEND_EMPTY"));
            assertEquals(EmptyDataHandling.SEND_EMPTY, EmptyDataHandling.valueOf("SEND_EMPTY"));
        }

        @Test
        @DisplayName("should contain SKIP")
        void shouldContainSkip() {
            assertNotNull(EmptyDataHandling.valueOf("SKIP"));
            assertEquals(EmptyDataHandling.SKIP, EmptyDataHandling.valueOf("SKIP"));
        }
    }

    @Nested
    @DisplayName("enum behavior")
    class EnumBehaviorTests {

        @Test
        @DisplayName("ERROR should be ordinal 0")
        void errorShouldBeFirst() {
            assertEquals(0, EmptyDataHandling.ERROR.ordinal());
        }

        @Test
        @DisplayName("ALLOW should be ordinal 1")
        void allowShouldBeSecond() {
            assertEquals(1, EmptyDataHandling.ALLOW.ordinal());
        }

        @Test
        @DisplayName("SEND_EMPTY should be ordinal 2")
        void sendEmptyShouldBeThird() {
            assertEquals(2, EmptyDataHandling.SEND_EMPTY.ordinal());
        }

        @Test
        @DisplayName("SKIP should be ordinal 3")
        void skipShouldBeFourth() {
            assertEquals(3, EmptyDataHandling.SKIP.ordinal());
        }

        @Test
        @DisplayName("should return correct name for each enum constant")
        void namesShouldMatch() {
            assertEquals("ERROR", EmptyDataHandling.ERROR.name());
            assertEquals("ALLOW", EmptyDataHandling.ALLOW.name());
            assertEquals("SEND_EMPTY", EmptyDataHandling.SEND_EMPTY.name());
            assertEquals("SKIP", EmptyDataHandling.SKIP.name());
        }
    }
}
