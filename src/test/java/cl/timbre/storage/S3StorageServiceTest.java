package cl.timbre.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("S3StorageService Implementation Tests")
@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    @Mock
    private S3Client mockS3Client;

    private S3StorageService s3StorageService;
    private static final String TEST_BUCKET = "test-bucket";
    private static final String TEST_KEY = "TEST_EMISOR/documents/doc-uuid-1.xml";

    @BeforeEach
    void setUp() {
        s3StorageService = new S3StorageService(mockS3Client, TEST_BUCKET);
    }

    @Test
    @DisplayName("put(String, byte[]) should succeed and return StorageResult with stored=true")
    void testPutByteArraySuccess() throws StorageException {
        // Arrange
        byte[] testData = "test content".getBytes();

        // Act
        StorageResult result = s3StorageService.put(TEST_KEY, testData);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_KEY, result.key());
        assertTrue(result.stored());
        assertFalse(result.wasFallback());
        verify(mockS3Client, times(1)).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    @DisplayName("put(String, InputStream) should succeed and return StorageResult with stored=true")
    void testPutInputStreamSuccess() throws StorageException {
        // Arrange
        byte[] testData = "test content".getBytes();
        InputStream inputStream = new ByteArrayInputStream(testData);

        // Act
        StorageResult result = s3StorageService.put(TEST_KEY, inputStream);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_KEY, result.key());
        assertTrue(result.stored());
        assertFalse(result.wasFallback());
        verify(mockS3Client, times(1)).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    @DisplayName("put() should throw StorageException on S3Exception")
    void testPutThrowsStorageExceptionOnS3Failure() {
        // Arrange
        byte[] testData = "test content".getBytes();
        S3Exception s3Exception = (S3Exception) S3Exception.builder()
                .message("Access Denied")
                .statusCode(403)
                .build();

        doThrow(s3Exception).when(mockS3Client).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));

        // Act & Assert
        StorageException thrown = assertThrows(StorageException.class, () ->
                s3StorageService.put(TEST_KEY, testData)
        );

        assertTrue(thrown.getMessage().contains("Failed to store key"));
        verify(mockS3Client, times(1)).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    @DisplayName("get(String) should succeed and return InputStream")
    void testGetSuccess() throws StorageException {
        // Arrange
        byte[] testData = "test content".getBytes();
        ResponseBytes<GetObjectResponse> responseBytes = ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(),
                testData
        );

        when(mockS3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(responseBytes);

        // Act
        InputStream result = s3StorageService.get(TEST_KEY);

        // Assert
        assertNotNull(result);
        verify(mockS3Client, times(1)).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    @DisplayName("get() should throw StorageException when key not found")
    void testGetThrowsStorageExceptionOnKeyNotFound() {
        // Arrange
        NoSuchKeyException noSuchKeyException = (NoSuchKeyException) NoSuchKeyException.builder()
                .message("The specified key does not exist")
                .build();

        doThrow(noSuchKeyException).when(mockS3Client).getObjectAsBytes(any(GetObjectRequest.class));

        // Act & Assert
        StorageException thrown = assertThrows(StorageException.class, () ->
                s3StorageService.get(TEST_KEY)
        );

        assertTrue(thrown.getMessage().contains("Key not found"));
        verify(mockS3Client, times(1)).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    @DisplayName("getBytes(String) should succeed and return byte array")
    void testGetBytesSuccess() throws StorageException {
        // Arrange
        byte[] testData = "test content".getBytes();
        ResponseBytes<GetObjectResponse> responseBytes = ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(),
                testData
        );

        when(mockS3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(responseBytes);

        // Act
        byte[] result = s3StorageService.getBytes(TEST_KEY);

        // Assert
        assertNotNull(result);
        assertArrayEquals(testData, result);
        verify(mockS3Client, times(1)).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    @DisplayName("delete(String) should succeed without throwing")
    void testDeleteSuccess() {
        // Arrange - no exception setup needed

        // Act & Assert - should not throw
        assertDoesNotThrow(() -> s3StorageService.delete(TEST_KEY));
        verify(mockS3Client, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }
}
