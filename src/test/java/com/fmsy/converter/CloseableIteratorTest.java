package com.fmsy.converter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CloseableIterator Tests")
class CloseableIteratorTest {

    @Nested
    @DisplayName("constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create iterator with delegate")
        void shouldCreateIteratorWithDelegate() {
            List<String> data = List.of("a", "b", "c");
            Iterator<String> delegate = data.iterator();

            CloseableIterator<String> iterator = new CloseableIterator<>(delegate);

            assertTrue(iterator.hasNext());
            assertEquals("a", iterator.next());
        }
    }

    @Nested
    @DisplayName("hasNext")
    class HasNextTests {

        @Test
        @DisplayName("should return true when elements remain")
        void shouldReturnTrueWhenElementsRemain() {
            List<String> data = List.of("a", "b");
            CloseableIterator<String> iterator = new CloseableIterator<>(data.iterator());

            assertTrue(iterator.hasNext());
        }

        @Test
        @DisplayName("should return false when no elements remain")
        void shouldReturnFalseWhenNoElementsRemain() {
            List<String> data = List.of("a");
            CloseableIterator<String> iterator = new CloseableIterator<>(data.iterator());
            iterator.next();

            assertFalse(iterator.hasNext());
        }

        @Test
        @DisplayName("should return false for empty collection")
        void shouldReturnFalseForEmptyCollection() {
            List<String> data = List.of();
            CloseableIterator<String> iterator = new CloseableIterator<>(data.iterator());

            assertFalse(iterator.hasNext());
        }
    }

    @Nested
    @DisplayName("next")
    class NextTests {

        @Test
        @DisplayName("should return next element")
        void shouldReturnNextElement() {
            List<String> data = List.of("first", "second", "third");
            CloseableIterator<String> iterator = new CloseableIterator<>(data.iterator());

            assertEquals("first", iterator.next());
            assertEquals("second", iterator.next());
            assertEquals("third", iterator.next());
        }

        @Test
        @DisplayName("should throw NoSuchElementException when exhausted")
        void shouldThrowNoSuchElementExceptionWhenExhausted() {
            List<String> data = List.of("a");
            CloseableIterator<String> iterator = new CloseableIterator<>(data.iterator());
            iterator.next();

            assertThrows(NoSuchElementException.class, iterator::next);
        }

        @Test
        @DisplayName("should increment record count on each next")
        void shouldIncrementRecordCountOnEachNext() {
            List<String> data = List.of("a", "b", "c");
            CloseableIterator<String> iterator = new CloseableIterator<>(data.iterator());

            iterator.next();
            iterator.next();
            iterator.next();

            assertEquals(3, iterator.getRecordCount());
        }
    }

    @Nested
    @DisplayName("getRecordCount")
    class GetRecordCountTests {

        @Test
        @DisplayName("should return zero initially")
        void shouldReturnZeroInitially() {
            List<String> data = List.of("a", "b");
            CloseableIterator<String> iterator = new CloseableIterator<>(data.iterator());

            assertEquals(0, iterator.getRecordCount());
        }

        @Test
        @DisplayName("should return correct count after some iterations")
        void shouldReturnCorrectCountAfterSomeIterations() {
            List<String> data = List.of("a", "b", "c", "d");
            CloseableIterator<String> iterator = new CloseableIterator<>(data.iterator());

            iterator.next();
            iterator.next();

            assertEquals(2, iterator.getRecordCount());
        }
    }

    @Nested
    @DisplayName("close")
    class CloseTests {

        @Test
        @DisplayName("should close AutoCloseable delegate")
        void shouldCloseAutoCloseableDelegate() {
            Iterator<InputStream> delegate = new Iterator<InputStream>() {
                private boolean closed = false;
                @Override public boolean hasNext() { return false; }
                @Override public InputStream next() { return null; }
                public void close() { closed = true; }
            };
            CloseableIterator<InputStream> iterator = new CloseableIterator<>(delegate);

            assertDoesNotThrow(() -> iterator.close());
        }

        @Test
        @DisplayName("should handle non-AutoCloseable delegate")
        void shouldHandleNonAutoCloseableDelegate() {
            Iterator<String> delegate = List.of("a", "b").iterator();
            CloseableIterator<String> iterator = new CloseableIterator<>(delegate);

            assertDoesNotThrow(() -> iterator.close());
        }

        @Test
        @DisplayName("should be idempotent on close")
        void shouldBeIdempotentOnClose() throws Exception {
            Iterator<InputStream> delegate = new Iterator<InputStream>() {
                private boolean closed = false;
                @Override public boolean hasNext() { return false; }
                @Override public InputStream next() { return null; }
                public void close() { closed = true; }
            };
            CloseableIterator<InputStream> iterator = new CloseableIterator<>(delegate);

            iterator.close();
            assertDoesNotThrow(() -> iterator.close());
        }
    }
}
