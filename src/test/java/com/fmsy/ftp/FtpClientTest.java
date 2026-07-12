package com.fmsy.ftp;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FtpClient Tests")
class FtpClientTest {

    @Mock
    private FTPClient ftpClient;

    @Mock
    private FtpPool.FtpConfigHolder holder;

    private FtpClient client;

    @BeforeEach
    void setUp() {
        client = new FtpClient(ftpClient, holder);
    }

    @Nested
    @DisplayName("getClient accessor")
    class GetClientTests {

        @Test
        @DisplayName("should return underlying FTPClient")
        void shouldReturnUnderlyingFtpClient() {
            assertSame(ftpClient, client.getClient());
        }
    }

    @Nested
    @DisplayName("stream operations")
    class StreamOperationsTests {

        @Test
        @DisplayName("getInputStream should delegate to retrieveFileStream")
        void getInputStreamShouldDelegate() throws Exception {
            when(ftpClient.retrieveFileStream("/remote/file.txt"))
                    .thenReturn(new ByteArrayInputStream("data".getBytes()));

            InputStream is = client.getInputStream("/remote/file.txt");

            assertNotNull(is);
            verify(ftpClient).retrieveFileStream("/remote/file.txt");
        }

        @Test
        @DisplayName("getInputStream should throw on null path")
        void getInputStreamShouldRejectNullPath() {
            assertThrows(IllegalArgumentException.class,
                    () -> client.getInputStream(null));
        }

        @Test
        @DisplayName("getInputStream should reject path traversal")
        void getInputStreamShouldRejectPathTraversal() {
            assertThrows(IllegalArgumentException.class,
                    () -> client.getInputStream("/remote/../etc/passwd"));
        }

        @Test
        @DisplayName("getInputStream should call handleConnectionFailure on IOException")
        void getInputStreamShouldHandleConnectionFailure() throws Exception {
            when(ftpClient.retrieveFileStream(anyString()))
                    .thenThrow(new IOException("connection lost"));

            assertThrows(IOException.class,
                    () -> client.getInputStream("/remote/file.txt"));

            verify(holder).removeClient(ftpClient);
        }

        @Test
        @DisplayName("getOutputStream should delegate to storeFileStream")
        void getOutputStreamShouldDelegate() throws Exception {
            OutputStream mockOs = mock(OutputStream.class);
            when(ftpClient.storeFileStream("/remote/out.txt")).thenReturn(mockOs);

            OutputStream os = client.getOutputStream("/remote/out.txt");

            assertSame(mockOs, os);
            verify(ftpClient).storeFileStream("/remote/out.txt");
        }

        @Test
        @DisplayName("getOutputStream should reject null path")
        void getOutputStreamShouldRejectNullPath() {
            assertThrows(IllegalArgumentException.class,
                    () -> client.getOutputStream(null));
        }

        @Test
        @DisplayName("completePendingCommand should delegate to FTPClient")
        void completePendingCommandShouldDelegate() throws Exception {
            client.completePendingCommand();
            verify(ftpClient).completePendingCommand();
        }

        @Test
        @DisplayName("completePendingCommand should handle IOException gracefully")
        void completePendingCommandShouldHandleIOException() throws Exception {
            doThrow(new IOException("pending failed")).when(ftpClient).completePendingCommand();
            assertDoesNotThrow(() -> client.completePendingCommand());
            verify(holder).removeClient(ftpClient);
        }
    }

    @Nested
    @DisplayName("file operations")
    class FileOperationsTests {

        @Test
        @DisplayName("exists should return true when file found")
        void existsShouldReturnTrueWhenFileFound() throws Exception {
            when(ftpClient.printWorkingDirectory()).thenReturn("/");
            when(ftpClient.changeWorkingDirectory("/remote")).thenReturn(true);
            when(ftpClient.listNames()).thenReturn(new String[]{"file.txt", "other.txt"});

            boolean result = client.exists("/remote/file.txt");

            assertTrue(result);
            verify(ftpClient).changeWorkingDirectory("/remote");
        }

        @Test
        @DisplayName("exists should return false when file not found")
        void existsShouldReturnFalseWhenFileNotFound() throws Exception {
            when(ftpClient.printWorkingDirectory()).thenReturn("/");
            when(ftpClient.changeWorkingDirectory("/remote")).thenReturn(true);
            when(ftpClient.listNames()).thenReturn(new String[]{"other.txt"});

            boolean result = client.exists("/remote/file.txt");

            assertFalse(result);
        }

        @Test
        @DisplayName("exists should restore original working directory")
        void existsShouldRestoreCwd() throws Exception {
            when(ftpClient.printWorkingDirectory()).thenReturn("/original");
            when(ftpClient.changeWorkingDirectory("/remote")).thenReturn(true);
            when(ftpClient.listNames()).thenReturn(new String[]{});

            client.exists("/remote/file.txt");

            verify(ftpClient, times(2)).changeWorkingDirectory(anyString());
            verify(ftpClient).changeWorkingDirectory("/original");
        }

        @Test
        @DisplayName("mkdirs should create nested directories")
        void mkdirsShouldCreateNestedDirs() throws Exception {
            when(ftpClient.changeWorkingDirectory("/a")).thenReturn(true);
            when(ftpClient.changeWorkingDirectory("/a/b")).thenReturn(false);
            when(ftpClient.makeDirectory("/a/b")).thenReturn(true);
            when(ftpClient.changeWorkingDirectory("/a/b/c")).thenReturn(false);
            when(ftpClient.makeDirectory("/a/b/c")).thenReturn(true);

            boolean result = client.mkdirs("/a/b/c");

            assertTrue(result);
            verify(ftpClient).makeDirectory("/a/b");
            verify(ftpClient).makeDirectory("/a/b/c");
        }

        @Test
        @DisplayName("mkdirs should return false when directory creation fails")
        void mkdirsShouldReturnFalseWhenCreateFails() throws Exception {
            when(ftpClient.changeWorkingDirectory("/newdir")).thenReturn(false);
            when(ftpClient.makeDirectory("/newdir")).thenReturn(false);

            boolean result = client.mkdirs("/newdir");

            assertFalse(result);
        }

        @Test
        @DisplayName("mkdirs should reject path traversal")
        void mkdirsShouldRejectPathTraversal() {
            assertThrows(IllegalArgumentException.class,
                    () -> client.mkdirs("/valid/../../evil"));
        }

        @Test
        @DisplayName("rename should delegate to FTPClient")
        void renameShouldDelegate() throws Exception {
            when(ftpClient.rename("/from.txt", "/to.txt")).thenReturn(true);

            boolean result = client.rename("/from.txt", "/to.txt");

            assertTrue(result);
            verify(ftpClient).rename("/from.txt", "/to.txt");
        }

        @Test
        @DisplayName("rename should reject path traversal in from")
        void renameShouldRejectTraversalInFrom() {
            assertThrows(IllegalArgumentException.class,
                    () -> client.rename("/valid/../../from.txt", "/to.txt"));
        }

        @Test
        @DisplayName("rename should reject path traversal in to")
        void renameShouldRejectTraversalInTo() {
            assertThrows(IllegalArgumentException.class,
                    () -> client.rename("/from.txt", "/valid/../../to.txt"));
        }

        @Test
        @DisplayName("deleteFile should delegate to FTPClient")
        void deleteFileShouldDelegate() throws Exception {
            when(ftpClient.deleteFile("/remote/old.txt")).thenReturn(true);

            boolean result = client.deleteFile("/remote/old.txt");

            assertTrue(result);
            verify(ftpClient).deleteFile("/remote/old.txt");
        }

        @Test
        @DisplayName("deleteFile should reject null path")
        void deleteFileShouldRejectNullPath() {
            assertThrows(IllegalArgumentException.class,
                    () -> client.deleteFile(null));
        }

        @Test
        @DisplayName("deleteFile should call handleConnectionFailure on IOException")
        void deleteFileShouldHandleConnectionFailure() throws Exception {
            when(ftpClient.deleteFile(anyString())).thenThrow(new IOException("connection lost"));

            boolean result = client.deleteFile("/remote/file.txt");

            assertFalse(result);
            verify(holder).removeClient(ftpClient);
        }

        @Test
        @DisplayName("listFiles should delegate listNames")
        void listFilesShouldDelegate() throws Exception {
            when(ftpClient.printWorkingDirectory()).thenReturn("/");
            when(ftpClient.changeWorkingDirectory("/remote")).thenReturn(true);
            when(ftpClient.listNames("*.csv")).thenReturn(new String[]{"a.csv", "b.csv"});

            String[] files = client.listFiles("/remote/*.csv");

            assertArrayEquals(new String[]{"a.csv", "b.csv"}, files);
        }

        @Test
        @DisplayName("listFiles should return empty array when directory not found")
        void listFilesShouldReturnEmptyWhenDirNotFound() throws Exception {
            when(ftpClient.printWorkingDirectory()).thenReturn("/");
            when(ftpClient.changeWorkingDirectory("/nonexistent")).thenReturn(false);

            String[] files = client.listFiles("/nonexistent/*.csv");

            assertEquals(0, files.length);
        }

        @Test
        @DisplayName("listFiles should restore original working directory")
        void listFilesShouldRestoreCwd() throws Exception {
            when(ftpClient.printWorkingDirectory()).thenReturn("/original");
            when(ftpClient.changeWorkingDirectory("/remote")).thenReturn(true);
            when(ftpClient.listNames("*.txt")).thenReturn(new String[]{});

            client.listFiles("/remote/*.txt");

            verify(ftpClient).changeWorkingDirectory("/original");
        }
    }

    @Nested
    @DisplayName("close operation")
    class CloseTests {

        @Test
        @DisplayName("close should delegate to holder.returnClient")
        void closeShouldReturnClientToPool() {
            client.close();
            verify(holder).returnClient(client);
        }

        @Test
        @DisplayName("close should not throw when called multiple times")
        void closeShouldBeRepeatable() {
            client.close();
            client.close();
            verify(holder, times(2)).returnClient(client);
        }
    }

    @Nested
    @DisplayName("getFileSize")
    class GetFileSizeTests {

        @Test
        @DisplayName("should return size from mlistFile")
        void shouldReturnSizeFromMlistFile() throws Exception {
            FTPFile file = mock(FTPFile.class);
            when(file.getSize()).thenReturn(1024L);
            when(ftpClient.mlistFile("/remote/file.txt")).thenReturn(file);

            long size = client.getFileSize("/remote/file.txt");

            assertEquals(1024L, size);
        }

        @Test
        @DisplayName("should fallback to listFiles when mlistFile returns null")
        void shouldFallbackToListFiles() throws Exception {
            FTPFile[] files = new FTPFile[1];
            files[0] = mock(FTPFile.class);
            when(files[0].getSize()).thenReturn(2048L);

            when(ftpClient.mlistFile("/remote/file.txt")).thenReturn(null);
            when(ftpClient.printWorkingDirectory()).thenReturn("/");
            when(ftpClient.changeWorkingDirectory("/remote")).thenReturn(true);
            when(ftpClient.listFiles("file.txt")).thenReturn(files);

            long size = client.getFileSize("/remote/file.txt");

            assertEquals(2048L, size);
        }

        @Test
        @DisplayName("should return -1 when file not found via fallback")
        void shouldReturnMinusOneWhenFileNotFound() throws Exception {
            when(ftpClient.mlistFile("/remote/nonexistent.txt")).thenReturn(null);
            when(ftpClient.printWorkingDirectory()).thenReturn("/");
            when(ftpClient.changeWorkingDirectory("/remote")).thenReturn(true);
            when(ftpClient.listFiles("nonexistent.txt")).thenReturn(null);

            long size = client.getFileSize("/remote/nonexistent.txt");

            assertEquals(-1L, size);
        }
    }

    @Nested
    @DisplayName("computeMd5")
    class ComputeMd5Tests {

        @Test
        @DisplayName("should compute MD5 for valid file")
        void shouldComputeMd5() throws Exception {
            byte[] content = "hello".getBytes();
            when(ftpClient.retrieveFileStream("/remote/hello.txt"))
                    .thenReturn(new ByteArrayInputStream(content));

            String md5 = client.computeMd5("/remote/hello.txt");

            assertEquals("5d41402abc4b2a76b9719d911017c592", md5);
        }

        @Test
        @DisplayName("should return empty string on IOException")
        void shouldReturnEmptyOnIOException() throws Exception {
            when(ftpClient.retrieveFileStream(anyString()))
                    .thenThrow(new IOException("file not found"));

            String md5 = client.computeMd5("/remote/missing.txt");

            assertEquals("", md5);
        }

        @Test
        @DisplayName("should return empty string for null path due to validation")
        void shouldReturnEmptyForNullPath() {
            String md5 = client.computeMd5(null);
            assertEquals("", md5);
        }
    }

    @Nested
    @DisplayName("moveToErrorDir")
    class MoveToErrorDirTests {

        @Test
        @DisplayName("should create error dir and rename file")
        void shouldMoveFileToErrorDir() throws Exception {
            when(ftpClient.printWorkingDirectory()).thenReturn("/");
            when(ftpClient.changeWorkingDirectory("/remote/error")).thenReturn(false);
            when(ftpClient.makeDirectory("/remote/error")).thenReturn(true);
            when(ftpClient.changeWorkingDirectory("/remote")).thenReturn(true);
            when(ftpClient.listNames()).thenReturn(null);
            when(ftpClient.rename(eq("/remote/test.csv"), startsWith("/remote/error/test_")))
                    .thenReturn(true);

            String result = client.moveToErrorDir("/remote/test.csv");

            assertNotNull(result);
            assertTrue(result.startsWith("/remote/error/test_"));
            assertTrue(result.endsWith(".csv"));
            verify(ftpClient).makeDirectory("/remote/error");
        }

        @Test
        @DisplayName("should return null when error dir creation fails")
        void shouldReturnNullWhenErrorDirCreationFails() throws Exception {
            when(ftpClient.changeWorkingDirectory("/remote")).thenReturn(true);
            when(ftpClient.changeWorkingDirectory("/remote/error")).thenReturn(false);
            when(ftpClient.makeDirectory("/remote/error")).thenReturn(false);

            String result = client.moveToErrorDir("/remote/test.csv");

            assertNull(result);
        }

        @Test
        @DisplayName("should return null when rename fails")
        void shouldReturnNullWhenRenameFails() throws Exception {
            when(ftpClient.changeWorkingDirectory("/remote")).thenReturn(true);
            when(ftpClient.changeWorkingDirectory("/remote/error")).thenReturn(true);
            when(ftpClient.rename(anyString(), anyString())).thenReturn(false);

            String result = client.moveToErrorDir("/remote/test.csv");

            assertNull(result);
        }
    }
}
