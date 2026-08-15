package cl.timbre.storage;

/**
 * Checked exception thrown when storage operations fail.
 * Indicates a failure in the underlying storage provider (S3/R2/local filesystem).
 * Callers should handle this exception and implement fallback strategies.
 */
public class StorageException extends Exception {

    /**
     * Constructs a StorageException with the given message.
     *
     * @param message the error message
     */
    public StorageException(String message) {
        super(message);
    }

    /**
     * Constructs a StorageException with the given message and cause.
     *
     * @param message the error message
     * @param cause the underlying exception
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
