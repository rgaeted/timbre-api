package cl.timbre.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LocalStorageService Implementation Tests")
class LocalStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalStorageService localStorageService;
    private static final String TEST_KEY = "TEST_EMISOR/documents/doc-uuid-1.xml";
    private static final String TEST_PDF_KEY = "TEST_EMISOR/documents/doc-uuid-1.pdf";

    @BeforeEach
    void setUp() {
        localStorageService = new LocalStorageService(tempDir.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("put(String, byte[]) should succeed and create file on disk")
    void testPutByteArraySuccess() throws StorageException, IOException {
        // Arrange
        byte[] testData = "test xml content".getBytes();

        // Act
        StorageResult result = localStorageService.put(TEST_KEY, testData);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_KEY, result.key());
        assertTrue(result.stored());
        assertFalse(result.wasFallback());

        // Verify file was created
        Path expectedPath = tempDir.resolve(TEST_KEY);
        assertTrue(Files.exists(expectedPath));
        byte[] storedData = Files.readAllBytes(expectedPath);
        assertArrayEquals(testData, storedData);
    }

    @Test
    @DisplayName("put(String, InputStream) should succeed and create file on disk")
    void testPutInputStreamSuccess() throws StorageException, IOException {
        // Arrange
        byte[] testData = "test pdf content".getBytes();
        InputStream inputStream = new ByteArrayInputStream(testData);

        // Act
        StorageResult result = localStorageService.put(TEST_PDF_KEY, inputStream);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_PDF_KEY, result.key());
        assertTrue(result.stored());
        assertFalse(result.wasFallback());

        // Verify file was created
        Path expectedPath = tempDir.resolve(TEST_PDF_KEY);
        assertTrue(Files.exists(expectedPath));
        byte[] storedData = Files.readAllBytes(expectedPath);
        assertArrayEquals(testData, storedData);
    }

    @Test
    @DisplayName("put() should create parent directories if they don't exist")
    void testPutCreatesParentDirectories() throws StorageException, IOException {
        // Arrange
        byte[] testData = "test content".getBytes();
        String deepKey = "EMISOR_1/documents/subfolder/deep/doc-uuid-1.xml";

        // Act
        StorageResult result = localStorageService.put(deepKey, testData);

        // Assert
        assertTrue(result.stored());
        Path expectedPath = tempDir.resolve(deepKey);
        assertTrue(Files.exists(expectedPath));
    }

    @Test
    @DisplayName("get(String) should succeed and return InputStream")
    void testGetSuccess() throws StorageException, IOException {
        // Arrange
        byte[] testData = "test content".getBytes();
        localStorageService.put(TEST_KEY, testData);

        // Act
        InputStream result = localStorageService.get(TEST_KEY);

        // Assert
        assertNotNull(result);
        byte[] retrievedData = result.readAllBytes();
        assertArrayEquals(testData, retrievedData);
    }

    @Test
    @DisplayName("get() should throw StorageException when key not found")
    void testGetThrowsStorageExceptionOnKeyNotFound() {
        // Act & Assert
        StorageException thrown = assertThrows(StorageException.class, () ->
                localStorageService.get("NONEXISTENT_EMISOR/documents/nonexistent.xml")
        );

        assertTrue(thrown.getMessage().contains("Key not found"));
    }

    @Test
    @DisplayName("getBytes(String) should succeed and return byte array")
    void testGetBytesSuccess() throws StorageException, IOException {
        // Arrange
        byte[] testData = "test content for bytes".getBytes();
        localStorageService.put(TEST_KEY, testData);

        // Act
        byte[] result = localStorageService.getBytes(TEST_KEY);

        // Assert
        assertNotNull(result);
        assertArrayEquals(testData, result);
    }

    @Test
    @DisplayName("delete(String) should remove file from disk")
    void testDeleteSuccess() throws StorageException, IOException {
        // Arrange
        byte[] testData = "test content".getBytes();
        localStorageService.put(TEST_KEY, testData);
        Path expectedPath = tempDir.resolve(TEST_KEY);
        assertTrue(Files.exists(expectedPath));

        // Act
        localStorageService.delete(TEST_KEY);

        // Assert
        assertFalse(Files.exists(expectedPath));
    }

    @Test
    @DisplayName("delete() should not throw when key doesn't exist")
    void testDeleteNonexistentKeyDoesNotThrow() {
        // Act & Assert - should not throw
        assertDoesNotThrow(() ->
                localStorageService.delete("NONEXISTENT_EMISOR/documents/nonexistent.xml")
        );
    }
}
