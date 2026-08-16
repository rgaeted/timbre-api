package cl.timbre.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * LocalStorageService implements storage operations using the local filesystem.
 * Intended for development and testing only; never for production.
 * Uses a base directory to organize all stored files.
 *
 * Note: This class is instantiated by StorageConfig bean factory, not as a @Service component.
 */
public class LocalStorageService implements StorageService {

    private static final Logger logger = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path baseDir;

    /**
     * Constructs a LocalStorageService with the given base directory.
     *
     * @param baseDirPath the root directory for local file storage (must be writable)
     */
    public LocalStorageService(String baseDirPath) {
        this.baseDir = Paths.get(baseDirPath);
        try {
            Files.createDirectories(this.baseDir);
            logger.info("Initialized LocalStorageService with base directory: {}", this.baseDir.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to create base directory {}", this.baseDir, e);
            throw new RuntimeException("Failed to initialize LocalStorageService", e);
        }
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
            Path filePath = baseDir.resolve(key);

            // Ensure parent directories exist
            Files.createDirectories(filePath.getParent());

            // Write the file
            Files.write(filePath, data);
            logger.debug("Successfully stored key={} to local filesystem at {}", key, filePath.toAbsolutePath());
            return new StorageResult(key, true, false);
        } catch (IOException e) {
            logger.error("Failed to store key={} to local filesystem", key, e);
            throw new StorageException("Failed to store key " + key + " to local filesystem: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream get(String key) throws StorageException {
        try {
            Path filePath = baseDir.resolve(key);

            if (!Files.exists(filePath)) {
                logger.warn("Key not found in local filesystem: key={}", key);
                throw new StorageException("Key not found: " + key);
            }

            byte[] data = Files.readAllBytes(filePath);
            logger.debug("Successfully retrieved key={} from local filesystem", key);
            return new ByteArrayInputStream(data);
        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            logger.error("Failed to retrieve key={} from local filesystem", key, e);
            throw new StorageException("Failed to retrieve key " + key + " from local filesystem: " + e.getMessage(), e);
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
            Path filePath = baseDir.resolve(key);

            if (Files.exists(filePath)) {
                Files.delete(filePath);
                logger.debug("Successfully deleted key={} from local filesystem", key);
            } else {
                logger.warn("Key not found for deletion: key={}", key);
            }
        } catch (IOException e) {
            logger.warn("Failed to delete key={} from local filesystem", key, e);
        }
    }
}
