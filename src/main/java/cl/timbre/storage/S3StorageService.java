package cl.timbre.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * S3StorageService implements storage operations against AWS S3 or S3-compatible services (R2, MinIO).
 * Supports configurable endpoint for S3/R2/MinIO compatibility.
 * All operations log failures but do not leak errors to callers.
 */
@Service
public class S3StorageService implements StorageService {

    private static final Logger logger = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3Client;
    private final String bucketName;

    /**
     * Constructs an S3StorageService with the given S3Client and bucket name.
     *
     * @param s3Client the AWS SDK S3 client (configured with endpoint, credentials, region)
     * @param bucketName the target S3 bucket name
     */
    public S3StorageService(S3Client s3Client, String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    @Override
    public StorageResult put(String key, InputStream data) throws StorageException {
        try {
            byte[] bytes = data.readAllBytes();
            return put(key, bytes);
        } catch (IOException e) {
            logger.error("Failed to read InputStream for key={}", key, e);
            throw new StorageException("Failed to read input stream for key: " + key, e);
        }
    }

    @Override
    public StorageResult put(String key, byte[] data) throws StorageException {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(data));
            logger.debug("Successfully stored key={} to S3 bucket={}", key, bucketName);
            return new StorageResult(key, true, false);
        } catch (S3Exception e) {
            logger.error("S3 error storing key={}: {}", key, e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage(), e);
            throw new StorageException("Failed to store key " + key + " in S3: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error storing key={}", key, e);
            throw new StorageException("Unexpected error storing key " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream get(String key) throws StorageException {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            byte[] data = s3Client.getObjectAsBytes(request).asByteArray();
            logger.debug("Successfully retrieved key={} from S3 bucket={}", key, bucketName);
            return new ByteArrayInputStream(data);
        } catch (NoSuchKeyException e) {
            logger.warn("Key not found in S3: key={}", key);
            throw new StorageException("Key not found: " + key, e);
        } catch (S3Exception e) {
            logger.error("S3 error retrieving key={}: {}", key, e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage(), e);
            throw new StorageException("Failed to retrieve key " + key + " from S3: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error retrieving key={}", key, e);
            throw new StorageException("Unexpected error retrieving key " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] getBytes(String key) throws StorageException {
        InputStream stream = get(key);
        try {
            return stream.readAllBytes();
        } catch (IOException e) {
            logger.error("Failed to read bytes from stream for key={}", key, e);
            throw new StorageException("Failed to read bytes for key: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.deleteObject(request);
            logger.debug("Successfully deleted key={} from S3 bucket={}", key, bucketName);
        } catch (S3Exception e) {
            logger.warn("S3 error deleting key={}: {}", key, e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage(), e);
        } catch (Exception e) {
            logger.warn("Unexpected error deleting key={}", key, e);
        }
    }
}
