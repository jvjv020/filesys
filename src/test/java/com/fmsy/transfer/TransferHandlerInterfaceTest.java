package com.fmsy.transfer;

import com.fmsy.model.Command;
import com.fmsy.model.Result;
import com.fmsy.model.TransferConfig;
import com.fmsy.transfer.download.MultiNodeDownloadHandler;
import com.fmsy.transfer.download.SingleDownloadHandler;
import com.fmsy.transfer.download.SingleNodeDownloadHandler;
import com.fmsy.transfer.upload.MultiUploadHandler;
import com.fmsy.transfer.upload.SingleUploadHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TransferHandler interface and its implementations.
 *
 * Since TransferHandler is an interface, these tests verify:
 * 1. The interface declares the correct handle method
 * 2. All known implementations exist and are non-null
 * 3. All implementations have a handle method with the correct signature
 */
@DisplayName("TransferHandler Interface Tests")
class TransferHandlerInterfaceTest {

    @Nested
    @DisplayName("TransferHandler interface")
    class InterfaceTests {

        @Test
        @DisplayName("should declare handle method with correct signature")
        void shouldDeclareHandleMethodWithCorrectSignature() throws NoSuchMethodException {
            Method handleMethod = TransferHandler.class.getMethod("handle", Command.class, TransferConfig.class, Result.class);

            assertNotNull(handleMethod);
            assertEquals(void.class, handleMethod.getReturnType());
        }

        @Test
        @DisplayName("should be a functional interface with single abstract method")
        void shouldBeFunctionalInterface() {
            // TransferHandler has only one declared method (handle), making it a functional interface
            assertEquals(1, TransferHandler.class.getDeclaredMethods().length);
        }
    }

    @Nested
    @DisplayName("Handler implementations exist")
    class ImplementationExistenceTests {

        @Test
        @DisplayName("SingleUploadHandler should implement TransferHandler")
        void singleUploadHandlerShouldImplementTransferHandler() {
            assertTrue(TransferHandler.class.isAssignableFrom(SingleUploadHandler.class));
        }

        @Test
        @DisplayName("MultiUploadHandler should implement TransferHandler")
        void multiUploadHandlerShouldImplementTransferHandler() {
            assertTrue(TransferHandler.class.isAssignableFrom(MultiUploadHandler.class));
        }

        @Test
        @DisplayName("SingleDownloadHandler should implement TransferHandler")
        void singleDownloadHandlerShouldImplementTransferHandler() {
            assertTrue(TransferHandler.class.isAssignableFrom(SingleDownloadHandler.class));
        }

        @Test
        @DisplayName("SingleNodeDownloadHandler should implement TransferHandler")
        void singleNodeDownloadHandlerShouldImplementTransferHandler() {
            assertTrue(TransferHandler.class.isAssignableFrom(SingleNodeDownloadHandler.class));
        }

        @Test
        @DisplayName("MultiNodeDownloadHandler should implement TransferHandler")
        void multiNodeDownloadHandlerShouldImplementTransferHandler() {
            assertTrue(TransferHandler.class.isAssignableFrom(MultiNodeDownloadHandler.class));
        }
    }

    @Nested
    @DisplayName("All implementations have handle method")
    class HandleMethodSignatureTests {

        @Test
        @DisplayName("SingleUploadHandler should have handle method")
        void singleUploadHandlerShouldHaveHandleMethod() throws NoSuchMethodException {
            Method handleMethod = SingleUploadHandler.class.getMethod("handle", Command.class, TransferConfig.class, Result.class);
            assertNotNull(handleMethod);
            assertEquals(void.class, handleMethod.getReturnType());
        }

        @Test
        @DisplayName("MultiUploadHandler should have handle method")
        void multiUploadHandlerShouldHaveHandleMethod() throws NoSuchMethodException {
            Method handleMethod = MultiUploadHandler.class.getMethod("handle", Command.class, TransferConfig.class, Result.class);
            assertNotNull(handleMethod);
            assertEquals(void.class, handleMethod.getReturnType());
        }

        @Test
        @DisplayName("SingleDownloadHandler should have handle method")
        void singleDownloadHandlerShouldHaveHandleMethod() throws NoSuchMethodException {
            Method handleMethod = SingleDownloadHandler.class.getMethod("handle", Command.class, TransferConfig.class, Result.class);
            assertNotNull(handleMethod);
            assertEquals(void.class, handleMethod.getReturnType());
        }

        @Test
        @DisplayName("SingleNodeDownloadHandler should have handle method")
        void singleNodeDownloadHandlerShouldHaveHandleMethod() throws NoSuchMethodException {
            Method handleMethod = SingleNodeDownloadHandler.class.getMethod("handle", Command.class, TransferConfig.class, Result.class);
            assertNotNull(handleMethod);
            assertEquals(void.class, handleMethod.getReturnType());
        }

        @Test
        @DisplayName("MultiNodeDownloadHandler should have handle method")
        void multiNodeDownloadHandlerShouldHaveHandleMethod() throws NoSuchMethodException {
            Method handleMethod = MultiNodeDownloadHandler.class.getMethod("handle", Command.class, TransferConfig.class, Result.class);
            assertNotNull(handleMethod);
            assertEquals(void.class, handleMethod.getReturnType());
        }
    }

    @Nested
    @DisplayName("All implementations throw Exception on handle")
    class HandleExceptionSignatureTests {

        @Test
        @DisplayName("SingleUploadHandler handle should throw Exception")
        void singleUploadHandlerHandleShouldThrowException() throws NoSuchMethodException {
            Method handleMethod = SingleUploadHandler.class.getMethod("handle", Command.class, TransferConfig.class, Result.class);
            Class<?>[] exceptionTypes = handleMethod.getExceptionTypes();
            assertTrue(exceptionTypes.length > 0);
        }

        @Test
        @DisplayName("MultiUploadHandler handle should throw Exception")
        void multiUploadHandlerHandleShouldThrowException() throws NoSuchMethodException {
            Method handleMethod = MultiUploadHandler.class.getMethod("handle", Command.class, TransferConfig.class, Result.class);
            Class<?>[] exceptionTypes = handleMethod.getExceptionTypes();
            assertTrue(exceptionTypes.length > 0);
        }

        @Test
        @DisplayName("SingleDownloadHandler handle should throw Exception")
        void singleDownloadHandlerHandleShouldThrowException() throws NoSuchMethodException {
            Method handleMethod = SingleDownloadHandler.class.getMethod("handle", Command.class, TransferConfig.class, Result.class);
            Class<?>[] exceptionTypes = handleMethod.getExceptionTypes();
            assertTrue(exceptionTypes.length > 0);
        }

        @Test
        @DisplayName("SingleNodeDownloadHandler handle should throw Exception")
        void singleNodeDownloadHandlerHandleShouldThrowException() throws NoSuchMethodException {
            Method handleMethod = SingleNodeDownloadHandler.class.getMethod("handle", Command.class, TransferConfig.class, Result.class);
            Class<?>[] exceptionTypes = handleMethod.getExceptionTypes();
            assertTrue(exceptionTypes.length > 0);
        }

        @Test
        @DisplayName("MultiNodeDownloadHandler handle should throw Exception")
        void multiNodeDownloadHandlerHandleShouldThrowException() throws NoSuchMethodException {
            Method handleMethod = MultiNodeDownloadHandler.class.getMethod("handle", Command.class, TransferConfig.class, Result.class);
            Class<?>[] exceptionTypes = handleMethod.getExceptionTypes();
            assertTrue(exceptionTypes.length > 0);
        }
    }
}
