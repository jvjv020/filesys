package com.fmsy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AsyncConfig Tests")
class AsyncConfigTest {

    @Nested
    @DisplayName("batchExecutorFactory bean")
    class BatchExecutorFactoryTests {

        private final AsyncConfig asyncConfig = new AsyncConfig();

        @Test
        @DisplayName("should return a factory function")
        void shouldReturnFactoryFunction() {
            IntFunction<ExecutorService> factory = asyncConfig.batchExecutorFactory();
            assertNotNull(factory);
        }

        @Test
        @DisplayName("factory should create ExecutorService with given batch size")
        void factoryShouldCreateExecutorWithGivenSize() {
            IntFunction<ExecutorService> factory = asyncConfig.batchExecutorFactory();
            ExecutorService executor = factory.apply(5);
            assertNotNull(executor);
            assertFalse(executor.isShutdown());
            executor.shutdown();
        }

        @Test
        @DisplayName("factory should create executor even with zero batch size")
        void factoryShouldHandleZeroBatchSize() {
            IntFunction<ExecutorService> factory = asyncConfig.batchExecutorFactory();
            assertDoesNotThrow(() -> {
                ExecutorService executor = factory.apply(0);
                assertNotNull(executor);
                executor.shutdown();
            });
        }

        @Test
        @DisplayName("factory should create executor even with negative batch size")
        void factoryShouldHandleNegativeBatchSize() {
            IntFunction<ExecutorService> factory = asyncConfig.batchExecutorFactory();
            assertDoesNotThrow(() -> {
                ExecutorService executor = factory.apply(-1);
                assertNotNull(executor);
                executor.shutdown();
            });
        }

        @Test
        @DisplayName("each factory invocation should create a new executor instance")
        void eachFactoryInvocationShouldCreateNewInstance() {
            IntFunction<ExecutorService> factory = asyncConfig.batchExecutorFactory();
            ExecutorService first = factory.apply(3);
            ExecutorService second = factory.apply(3);
            assertNotSame(first, second);
            first.shutdown();
            second.shutdown();
        }

        @Test
        @DisplayName("factory should be reusable after executor shutdown")
        void factoryShouldBeReusable() {
            IntFunction<ExecutorService> factory = asyncConfig.batchExecutorFactory();
            ExecutorService first = factory.apply(2);
            first.shutdown();
            assertDoesNotThrow(() -> {
                ExecutorService second = factory.apply(2);
                assertNotNull(second);
                second.shutdown();
            });
        }
    }
}
