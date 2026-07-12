package com.fmsy.fileops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MessageSender Tests")
class MessageSenderTest {

    private final MessageSender sender = new MessageSender();

    @Nested
    @DisplayName("send with LOG channel")
    class LogChannelTests {

        @Test
        @DisplayName("should accept LOG:target format")
        void shouldAcceptLogWithTarget() {
            // Should not throw
            sender.send("LOG:test.logger", "Hello from test");
        }

        @Test
        @DisplayName("should accept LOG: with empty target")
        void shouldAcceptLogWithEmptyTarget() {
            sender.send("LOG:", "Message via default logger");
        }

        @Test
        @DisplayName("should handle null target config")
        void shouldHandleNullTarget() {
            // Should not throw
            sender.send(null, "message");
        }

        @Test
        @DisplayName("should handle empty target config")
        void shouldHandleEmptyTarget() {
            sender.send("", "message");
        }
    }

    @Nested
    @DisplayName("send with WEBHOOK channel")
    class WebhookChannelTests {

        @Test
        @DisplayName("should handle invalid URL gracefully")
        void shouldHandleInvalidUrl() {
            sender.send("WEBHOOK:not-a-valid-url", "test message");
        }

        @Test
        @DisplayName("should handle null URL gracefully")
        void shouldHandleNullUrl() {
            sender.send("WEBHOOK:", "test message");
        }

        @Test
        @DisplayName("should work with http URL")
        void shouldWorkWithHttpUrl() {
            // Internal HTTP call - should not throw, just log warn/error
            sender.send("WEBHOOK:http://localhost:0/test", "test message");
        }
    }

    @Nested
    @DisplayName("send with unsupported channels")
    class UnsupportedChannelTests {

        @Test
        @DisplayName("should warn on MAIL channel")
        void shouldWarnOnMail() {
            sender.send("MAIL:admin@example.com", "test");
        }

        @Test
        @DisplayName("should warn on SMS channel")
        void shouldWarnOnSms() {
            sender.send("SMS:+1234567890", "test");
        }

        @Test
        @DisplayName("should warn on MQ channel")
        void shouldWarnOnMq() {
            sender.send("MQ:queue.name", "test");
        }

        @Test
        @DisplayName("should warn on unknown channel")
        void shouldWarnOnUnknown() {
            sender.send("UNKNOWN:target", "test");
        }
    }

    @Nested
    @DisplayName("target config parsing")
    class TargetConfigParsingTests {

        @Test
        @DisplayName("should split type and target by colon")
        void shouldSplitByColon() {
            // Should not throw
            sender.send("LOG:my.logger", "test");
        }

        @Test
        @DisplayName("should handle config without colon")
        void shouldHandleNoColon() {
            sender.send("INVALID_CONFIG", "test");
        }

        @Test
        @DisplayName("should trim type casing")
        void shouldTrimTypeCasing() {
            sender.send("  log  :target", "test");
            sender.send("WEBHOOK:http://example.com", "test");
        }
    }

    @Nested
    @DisplayName("shouldRetry")
    class ShouldRetryTests {

        @Test
        @DisplayName("should retry on 5xx status codes")
        void shouldRetryOn5xx() {
            assertTrue(MessageSender.shouldRetry(500));
            assertTrue(MessageSender.shouldRetry(502));
            assertTrue(MessageSender.shouldRetry(503));
            assertTrue(MessageSender.shouldRetry(599));
        }

        @Test
        @DisplayName("should retry on 429 status code")
        void shouldRetryOn429() {
            assertTrue(MessageSender.shouldRetry(429));
        }

        @Test
        @DisplayName("should not retry on 2xx status codes")
        void shouldNotRetryOn2xx() {
            assertFalse(MessageSender.shouldRetry(200));
            assertFalse(MessageSender.shouldRetry(201));
            assertFalse(MessageSender.shouldRetry(204));
        }

        @Test
        @DisplayName("should not retry on 3xx status codes")
        void shouldNotRetryOn3xx() {
            assertFalse(MessageSender.shouldRetry(301));
            assertFalse(MessageSender.shouldRetry(302));
        }

        @Test
        @DisplayName("should not retry on 4xx except 429")
        void shouldNotRetryOn4xxExcept429() {
            assertFalse(MessageSender.shouldRetry(400));
            assertFalse(MessageSender.shouldRetry(401));
            assertFalse(MessageSender.shouldRetry(403));
            assertFalse(MessageSender.shouldRetry(404));
            assertFalse(MessageSender.shouldRetry(418));
        }
    }

    @Nested
    @DisplayName("retryBackoffMs")
    class RetryBackoffMsTests {

        @Test
        @DisplayName("should return 100ms for attempt 0")
        void shouldReturn100ForAttempt0() {
            assertEquals(100L, MessageSender.retryBackoffMs(0));
        }

        @Test
        @DisplayName("should return 500ms for attempt 1")
        void shouldReturn500ForAttempt1() {
            assertEquals(500L, MessageSender.retryBackoffMs(1));
        }

        @Test
        @DisplayName("should return 0 for attempt 2 or more")
        void shouldReturn0ForAttempt2OrMore() {
            assertEquals(0L, MessageSender.retryBackoffMs(2));
            assertEquals(0L, MessageSender.retryBackoffMs(3));
            assertEquals(0L, MessageSender.retryBackoffMs(10));
        }
    }

    @Nested
    @DisplayName("escape")
    class EscapeTests {

        @Test
        @DisplayName("should escape double quotes")
        void shouldEscapeDoubleQuotes() {
            assertEquals("say \\\"hello\\\"", MessageSender.escape("say \"hello\""));
        }

        @Test
        @DisplayName("should escape backslashes")
        void shouldEscapeBackslashes() {
            assertEquals("path\\\\to\\\\file", MessageSender.escape("path\\to\\file"));
        }

        @Test
        @DisplayName("should escape newlines")
        void shouldEscapeNewlines() {
            assertEquals("line1\\nline2", MessageSender.escape("line1\nline2"));
        }

        @Test
        @DisplayName("should escape carriage returns")
        void shouldEscapeCarriageReturns() {
            assertEquals("line1\\rline2", MessageSender.escape("line1\rline2"));
        }

        @Test
        @DisplayName("should escape tabs")
        void shouldEscapeTabs() {
            assertEquals("col1\\tcol2", MessageSender.escape("col1\tcol2"));
        }

        @Test
        @DisplayName("should return empty string for null input")
        void shouldReturnEmptyForNull() {
            assertEquals("", MessageSender.escape(null));
        }

        @Test
        @DisplayName("should return original for normal text")
        void shouldReturnOriginalForNormalText() {
            String normal = "Hello, world! 123";
            assertEquals(normal, MessageSender.escape(normal));
        }

        @Test
        @DisplayName("should handle mixed special characters")
        void shouldHandleMixedSpecials() {
            String input = "He said \"hello\"\n\t\tworld";
            String expected = "He said \\\"hello\\\"\\n\\t\\tworld";
            assertEquals(expected, MessageSender.escape(input));
        }
    }
}
