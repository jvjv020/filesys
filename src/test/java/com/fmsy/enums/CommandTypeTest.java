package com.fmsy.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CommandType Tests")
class CommandTypeTest {

    @Nested
    @DisplayName("enum values")
    class EnumValuesTests {

        @Test
        @DisplayName("SERIAL should have null code")
        void serialShouldHaveNullCode() {
            assertNull(CommandType.SERIAL.code());
        }

        @Test
        @DisplayName("BATCH should have R code")
        void batchShouldHaveRCode() {
            assertEquals("R", CommandType.BATCH.code());
        }

        @Test
        @DisplayName("COORDINATED should have S code")
        void coordinatedShouldHaveSCode() {
            assertEquals("S", CommandType.COORDINATED.code());
        }

        @Test
        @DisplayName("TEMPORARY should have T code")
        void temporaryShouldHaveTCode() {
            assertEquals("T", CommandType.TEMPORARY.code());
        }

        @Test
        @DisplayName("should have exactly 4 enum values")
        void shouldHaveExactlyFourEnumValues() {
            assertEquals(4, CommandType.values().length);
        }
    }

    @Nested
    @DisplayName("fromCode() method")
    class FromCodeTests {

        @ParameterizedTest
        @CsvSource({
            "null, SERIAL",
            "'', SERIAL",
            "R, BATCH",
            "S, COORDINATED",
            "T, TEMPORARY"
        })
        @DisplayName("should return correct CommandType for valid codes")
        void shouldReturnCorrectCommandTypeForValidCodes(String code, CommandType expected) {
            assertEquals(expected, CommandType.fromCode(code));
        }

        @Test
        @DisplayName("should return SERIAL for null code")
        void shouldReturnSerialForNullCode() {
            assertEquals(CommandType.SERIAL, CommandType.fromCode(null));
        }

        @Test
        @DisplayName("should return SERIAL for empty string")
        void shouldReturnSerialForEmptyString() {
            assertEquals(CommandType.SERIAL, CommandType.fromCode(""));
        }

        @Test
        @DisplayName("should return SERIAL for unknown code")
        void shouldReturnSerialForUnknownCode() {
            assertEquals(CommandType.SERIAL, CommandType.fromCode("UNKNOWN"));
            assertEquals(CommandType.SERIAL, CommandType.fromCode("X"));
            assertEquals(CommandType.SERIAL, CommandType.fromCode("P"));
        }

        @Test
        @DisplayName("should be case sensitive for non-null codes")
        void shouldBeCaseSensitiveForNonNullCodes() {
            assertEquals(CommandType.BATCH, CommandType.fromCode("R"));
            assertEquals(CommandType.COORDINATED, CommandType.fromCode("S"));
        }
    }
}
