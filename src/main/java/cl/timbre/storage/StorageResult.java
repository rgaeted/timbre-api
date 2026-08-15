package cl.timbre.storage;

/**
 * Result of a storage operation (put).
 * Provides information about whether data was stored to primary storage or fallback.
 *
 * @param key the storage key where data was stored
 * @param stored true if data was successfully written to primary storage (S3/R2/local),
 *               false if it fell back to BYTEA columns
 * @param wasFallback true if this operation used fallback storage (BYTEA),
 *                    false if primary storage was used
 */
public record StorageResult(
    String key,
    boolean stored,
    boolean wasFallback
) { }
