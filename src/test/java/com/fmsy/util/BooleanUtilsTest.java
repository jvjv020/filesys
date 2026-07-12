package com.fmsy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BooleanUtils Tests")
class BooleanUtilsTest {

    @Nested
    @DisplayName("isYes() method")
    class IsYesTests {

        @Test
        @DisplayName("should return true for uppercase Y")
        void shouldReturnTrueForUppercaseY() {
            assertTrue(BooleanUtils.isYes("Y"));
        }

        @Test
        @DisplayName("should return true for lowercase y")
        void shouldReturnTrueForLowercaseY() {
            assertTrue(BooleanUtils.isYes("y"));
        }

        @Test
        @DisplayName("should return true for mixed case y")
        void shouldReturnTrueForMixedCaseY() {
            assertTrue(BooleanUtils.isYes("Y"));
            assertTrue(BooleanUtils.isYes("y"));
        }

        @Test
        @DisplayName("should return false for null")
        void shouldReturnFalseForNull() {
            assertFalse(BooleanUtils.isYes(null));
        }

        @Test
        @DisplayName("should return false for empty string")
        void shouldReturnFalseForEmptyString() {
            assertFalse(BooleanUtils.isYes(""));
        }

        @Test
        @DisplayName("should return false for whitespace")
        void shouldReturnFalseForWhitespace() {
            assertFalse(BooleanUtils.isYes("   "));
        }

        @Test
        @DisplayName("should return false for N")
        void shouldReturnFalseForN() {
            assertFalse(BooleanUtils.isYes("N"));
            assertFalse(BooleanUtils.isYes("n"));
        }

        @Test
        @DisplayName("should return false for YES")
        void shouldReturnFalseForYES() {
            assertFalse(BooleanUtils.isYes("YES"));
            assertFalse(BooleanUtils.isYes("Yes"));
        }

        @Test
        @DisplayName("should return false for other strings")
        void shouldReturnFalseForOtherStrings() {
            assertFalse(BooleanUtils.isYes("true"));
            assertFalse(BooleanUtils.isYes("1"));
            assertFalse(BooleanUtils.isYes("Y "));
            assertFalse(BooleanUtils.isYes(" Y"));
        }
    }
}
