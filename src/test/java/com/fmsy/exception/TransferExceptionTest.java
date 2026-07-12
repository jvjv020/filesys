package com.fmsy.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TransferException Tests")
class TransferExceptionTest {

    @Nested
    @DisplayName("constructor with message only")
    class MessageOnlyConstructorTests {

        @Test
        @DisplayName("should store the message")
        void shouldStoreMessage() {
            TransferException ex = new TransferException("test message");
            assertEquals("test message", ex.getMessage());
        }

        @Test
        @DisplayName("should set default error code")
        void shouldSetDefaultErrorCode() {
            TransferException ex = new TransferException("test message");
            assertEquals("TRANSFER_ERROR", ex.getErrorCode());
        }

        @Test
        @DisplayName("should have null cause")
        void shouldHaveNullCause() {
            TransferException ex = new TransferException("test message");
            assertNull(ex.getCause());
        }
    }

    @Nested
    @DisplayName("constructor with message and cause")
    class MessageAndCauseConstructorTests {

        @Test
        @DisplayName("should store both message and cause")
        void shouldStoreMessageAndCause() {
            RuntimeException cause = new RuntimeException("root cause");
            TransferException ex = new TransferException("test message", cause);
            assertEquals("test message", ex.getMessage());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("should set default error code")
        void shouldSetDefaultErrorCode() {
            TransferException ex = new TransferException("test message", new RuntimeException());
            assertEquals("TRANSFER_ERROR", ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("constructor with errorCode and message")
    class ErrorCodeAndMessageConstructorTests {

        @Test
        @DisplayName("should store custom error code and message")
        void shouldStoreErrorCodeAndMessage() {
            TransferException ex = new TransferException("CONFIG_NOT_FOUND", "config missing");
            assertEquals("CONFIG_NOT_FOUND", ex.getErrorCode());
            assertEquals("config missing", ex.getMessage());
        }

        @Test
        @DisplayName("should support FTP error codes")
        void shouldSupportFtpErrorCodes() {
            TransferException ex = new TransferException("FTP_CONNECTION_FAILED",
                    "FTP连接失败: 192.168.1.1");
            assertEquals("FTP_CONNECTION_FAILED", ex.getErrorCode());
        }

        @Test
        @DisplayName("should have null cause")
        void shouldHaveNullCause() {
            TransferException ex = new TransferException("ERROR_CODE", "message");
            assertNull(ex.getCause());
        }
    }

    @Nested
    @DisplayName("constructor with errorCode, message and cause")
    class FullConstructorTests {

        @Test
        @DisplayName("should store all fields")
        void shouldStoreAllFields() {
            RuntimeException cause = new RuntimeException("db error");
            TransferException ex = new TransferException("DB_ERROR", "database failed", cause);
            assertEquals("DB_ERROR", ex.getErrorCode());
            assertEquals("database failed", ex.getMessage());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("should propagate chained causes")
        void shouldPropagateChainedCause() {
            Exception inner = new IllegalArgumentException("invalid arg");
            RuntimeException outer = new RuntimeException("wrapped", inner);
            TransferException ex = new TransferException("VALIDATION", "validation failed", outer);
            assertSame(outer, ex.getCause());
            assertSame(inner, ex.getCause().getCause());
        }
    }

    @Nested
    @DisplayName("inheritance")
    class InheritanceTests {

        @Test
        @DisplayName("should be a RuntimeException")
        void shouldBeRuntimeException() {
            TransferException ex = new TransferException("test");
            assertInstanceOf(RuntimeException.class, ex);
        }
    }
}
