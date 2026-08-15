package cl.timbre.storage;

import java.io.InputStream;

/**
 * Abstraction layer for document storage operations.
 * Implementations provide pluggable storage backends (S3, R2, local filesystem).
 * All methods are non-blocking; failures are handled via fallback to BYTEA columns.
 */
public interface StorageService {

    /**
     * Store data from an InputStream at the given key.
     *
     * @param key the storage key (format: {emisorId}/documents/{documentId}.{extension})
     * @param data input stream containing the data to store
     * @return StorageResult with success status and key
     * @throws StorageException if underlying storage operation fails
     */
    StorageResult put(String key, InputStream data) throws StorageException;

    /**
     * Store data from a byte array at the given key.
     *
     * @param key the storage key (format: {emisorId}/documents/{documentId}.{extension})
     * @param data byte array containing the data to store
     * @return StorageResult with success status and key
     * @throws StorageException if underlying storage operation fails
     */
    StorageResult put(String key, byte[] data) throws StorageException;

    /**
     * Retrieve data from storage as an InputStream.
     *
     * @param key the storage key to retrieve
     * @return InputStream containing the stored data
     * @throws StorageException if the key does not exist or retrieval fails
     */
    InputStream get(String key) throws StorageException;

    /**
     * Retrieve data from storage as a byte array.
     *
     * @param key the storage key to retrieve
     * @return byte array containing the stored data
     * @throws StorageException if the key does not exist or retrieval fails
     */
    byte[] getBytes(String key) throws StorageException;

    /**
     * Delete data at the given key.
     * Non-blocking; errors are logged but do not throw.
     *
     * @param key the storage key to delete
     */
    void delete(String key);
}
