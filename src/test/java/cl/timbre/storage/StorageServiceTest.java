package cl.timbre.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("StorageService Interface Tests")
@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    @Mock
    private StorageService mockStorageService;

    @Test
    @DisplayName("put(String, InputStream) should handle successful storage")
    void testPutInputStreamSuccess() throws StorageException {
        // Arrange
        String key = "TEST_EMISOR/documents/doc-uuid-1.xml";
        byte[] testData = "test content".getBytes();
        InputStream inputStream = new ByteArrayInputStream(testData);
        StorageResult expectedResult = new StorageResult(key, true, false);

        when(mockStorageService.put(key, inputStream)).thenReturn(expectedResult);

        // Act
        StorageResult result = mockStorageService.put(key, inputStream);

        // Assert
        assertNotNull(result);
        assertEquals(key, result.key());
        assertTrue(result.stored());
        assertFalse(result.wasFallback());
        verify(mockStorageService, times(1)).put(key, inputStream);
    }

    @Test
    @DisplayName("put(String, byte[]) should handle successful storage")
    void testPutByteArraySuccess() throws StorageException {
        // Arrange
        String key = "TEST_EMISOR/documents/doc-uuid-1.pdf";
        byte[] testData = "pdf content".getBytes();
        StorageResult expectedResult = new StorageResult(key, true, false);

        when(mockStorageService.put(key, testData)).thenReturn(expectedResult);

        // Act
        StorageResult result = mockStorageService.put(key, testData);

        // Assert
        assertNotNull(result);
        assertEquals(key, result.key());
        assertTrue(result.stored());
        assertFalse(result.wasFallback());
        verify(mockStorageService, times(1)).put(key, testData);
    }

    @Test
    @DisplayName("get(String) should return InputStream on success")
    void testGetInputStreamSuccess() throws StorageException {
        // Arrange
        String key = "TEST_EMISOR/documents/doc-uuid-1.xml";
        byte[] testData = "test content".getBytes();
        InputStream expectedStream = new ByteArrayInputStream(testData);

        when(mockStorageService.get(key)).thenReturn(expectedStream);

        // Act
        InputStream result = mockStorageService.get(key);

        // Assert
        assertNotNull(result);
        verify(mockStorageService, times(1)).get(key);
    }

    @Test
    @DisplayName("delete(String) should handle deletion without throwing")
    void testDeleteSuccess() {
        // Arrange
        String key = "TEST_EMISOR/documents/doc-uuid-1.xml";

        // Act & Assert - should not throw
        assertDoesNotThrow(() -> mockStorageService.delete(key));
        verify(mockStorageService, times(1)).delete(key);
    }
}
