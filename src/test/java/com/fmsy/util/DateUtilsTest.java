package com.fmsy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DateUtils Tests")
class DateUtilsTest {

    @Nested
    @DisplayName("formatDate() method")
    class FormatDateTests {

        @Test
        @DisplayName("should format LocalDateTime to yyyyMMdd")
        void shouldFormatDateCorrectly() {
            LocalDateTime dateTime = LocalDateTime.of(2024, 3, 15, 10, 30, 45);
            String result = DateUtils.formatDate(dateTime);
            assertEquals("20240315", result);
        }

        @Test
        @DisplayName("should return empty string for null input")
        void shouldReturnEmptyStringForNull() {
            assertEquals("", DateUtils.formatDate(null));
        }

        @Test
        @DisplayName("should handle year boundary")
        void shouldHandleYearBoundary() {
            LocalDateTime dateTime = LocalDateTime.of(2023, 12, 31, 23, 59, 59);
            assertEquals("20231231", DateUtils.formatDate(dateTime));
        }
    }

    @Nested
    @DisplayName("formatDateTime() method")
    class FormatDateTimeTests {

        @Test
        @DisplayName("should format LocalDateTime to yyyyMMddHHmmss")
        void shouldFormatDateTimeCorrectly() {
            LocalDateTime dateTime = LocalDateTime.of(2024, 3, 15, 10, 30, 45);
            String result = DateUtils.formatDateTime(dateTime);
            assertEquals("20240315103045", result);
        }

        @Test
        @DisplayName("should return empty string for null input")
        void shouldReturnEmptyStringForNull() {
            assertEquals("", DateUtils.formatDateTime(null));
        }

        @Test
        @DisplayName("should handle midnight")
        void shouldHandleMidnight() {
            LocalDateTime dateTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
            assertEquals("20240101000000", DateUtils.formatDateTime(dateTime));
        }
    }

    @Nested
    @DisplayName("DateTimeFormatter constants")
    class FormatterConstantsTests {

        @Test
        @DisplayName("DATE_FORMATTER should format correctly")
        void dateFormatterShouldFormatCorrectly() {
            assertEquals("20240101", DateUtils.formatDate(LocalDateTime.of(2024, 1, 1, 0, 0, 0)));
        }

        @Test
        @DisplayName("DATETIME_FORMATTER should format correctly")
        void dateTimeFormatterShouldFormatCorrectly() {
            assertEquals("20240101000000", DateUtils.formatDateTime(LocalDateTime.of(2024, 1, 1, 0, 0, 0)));
        }
    }
}
