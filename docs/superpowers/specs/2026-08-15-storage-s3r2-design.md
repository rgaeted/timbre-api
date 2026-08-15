# Phase D: Storage S3/R2 Implementation Design

> **Overview:** Migrate Document XML and PDF storage from temporary BYTEA database columns to S3/R2 object storage, with graceful fallback to database and background sync job for resilience.

## Goal

Enable production-grade document storage by abstracting storage operations behind a pluggable `StorageService` interface supporting S3, R2, and local filesystem. Establish reliable, scalable persistence for large XML/PDF artifacts without overwhelming database throughput or capacity.

## Architecture

### Storage Abstraction Layer

**Core Interface: `StorageService`**
```java
public interface StorageService {
    StorageResult put(String key, InputStream data);
    StorageResult put(String key, byte[] data);
    InputStream get(String key) throws StorageException;
    byte[] getBytes(String key) throws StorageException;
    void delete(String key);
}
```

`StorageResult` record:
```java
public record StorageResult(
    String key,
    boolean stored,      // true if written to primary storage, false if fallback
    boolean wasFallback  // true if this operation used fallback (BYTEA)
)
```

**Implementations:**
1. `S3StorageService` — AWS SDK S3 client, compatible with S3/R2/Minio via endpoint override
2. `LocalStorageService` — filesystem-based (dev/test only, never production)

**Provider Selection:** Spring bean factory pattern selects implementation based on `storage.provider` config:
- `provider: s3` → S3StorageService
- `provider: local` → LocalStorageService

### Key Naming Convention

All storage keys follow this pattern:
```
{emisorId}/documents/{documentId}.{extension}
```

Examples:
- `RUT_EMISOR_SIN_DV/documents/doc-uuid-1.xml`
- `RUT_EMISOR_SIN_DV/documents/doc-uuid-1.pdf`

Rationale: Deterministic (same document ID = same key), hierarchical (bucket listing by emisor), collision-free.

### Graceful Fallback Strategy

When `StorageService.put()` fails:
1. Write data to `Document.xml_content` (String) or `Document.pdf_content` (byte[])
2. Leave `Document.xml_key` and `Document.pdf_key` null
3. Set `Document.stored_fallback` flag to true
4. Log warning, continue (non-blocking failure)

When `StorageService.get()` fails or key is null:
1. Fall back to reading from `Document.xml_content` / `Document.pdf_content`
2. If that's also null, return 404

**Background Sync Job (`StorageSyncJob`)**
- Runs periodically (configurable, default every 5 minutes)
- Queries: `SELECT * FROM document WHERE stored_fallback = true AND xml_key IS NULL LIMIT 100`
- For each document, attempt to migrate `xml_content` → S3, then `pdf_content` → S3
- On success: update keys, clear BYTEA columns, set `stored_fallback = false`
- On failure: log and retry next run (no max retries, operator monitors via logs)

### Database Schema Changes

Add one column to `document` table:
```sql
ALTER TABLE document ADD COLUMN stored_fallback BOOLEAN DEFAULT FALSE;
```

Existing columns (no changes, reuse):
- `xml_key VARCHAR(255)` — S3 key for XML (nullable)
- `xml_content TEXT` — fallback XML (nullable)
- `pdf_key VARCHAR(255)` — S3 key for PDF (nullable)
- `pdf_content BYTEA` — fallback PDF (nullable)

### Configuration

Config block already exists in `application.yml`. No changes needed, but clarification:

```yaml
storage:
  provider: ${STORAGE_PROVIDER:local}           # "s3" or "local"
  local-dir: ${STORAGE_LOCAL_DIR:storage}       # Only used if provider=local
  bucket: ${R2_BUCKET:}                         # S3/R2 bucket name
  endpoint: ${R2_ENDPOINT:}                     # S3/R2 endpoint URL (optional, uses AWS default if empty)
  region: ${R2_REGION:auto}                     # AWS region or "auto" for R2
  access-key: ${R2_ACCESS_KEY:}                 # AWS/R2 access key
  secret-key: ${R2_SECRET_KEY:}                 # AWS/R2 secret key
```

## Data Flow

### Emission (IssuanceService.issue())

```
1. Generate XML via DteXmlBuilder
2. Call storageService.put(xmlKey, xmlContent)
   → Success: xmlKey persisted, xmlContent = null
   → Failure: xmlContent persisted to DB, xmlKey = null, stored_fallback = true
3. Generate PDF via RideBuilder
4. Call storageService.put(pdfKey, pdfContent)
   → Success: pdfKey persisted, pdfContent = null
   → Failure: pdfContent persisted to DB, pdfKey = null, stored_fallback = true
5. Save Document with estado=PENDIENTE_ENVIO
   → Emission succeeds regardless of storage outcome
```

### Download (DocumentController)

**XML Endpoint:**
```
GET /api/v1/documents/{id}/xml
1. Fetch Document by id
2. if xmlKey != null: return storageService.get(xmlKey) [with StorageException → 500]
3. else if xmlContent != null: return xmlContent
4. else: return 404
```

**PDF Endpoint:**
```
GET /api/v1/documents/{id}/pdf
1. Fetch Document by id
2. if pdfKey != null: return storageService.get(pdfKey) [with StorageException → 500]
3. else if pdfContent != null: return pdfContent
4. else: return 404
```

### Background Sync (StorageSyncJob)

```
Every 5 minutes (configurable):
  1. SELECT * FROM document WHERE stored_fallback=true AND xml_key IS NULL LIMIT 100
  2. For each document:
       a. Attempt storageService.put(xmlKey, xmlContent)
          → Success: UPDATE xml_key=key, xml_content=NULL
          → Failure: log warning, skip
       b. Attempt storageService.put(pdfKey, pdfContent)
          → Success: UPDATE pdf_key=key, pdf_content=NULL
          → Failure: log warning, skip
       c. If both succeeded: UPDATE stored_fallback=false
  3. Log results: "Synced N documents, M failures"
```

## Error Handling

### StorageService Failures

- `StorageException` (checked) — underlying provider error, trigger fallback
- Connection timeouts: caught as StorageException, trigger fallback
- Authorization errors: caught as StorageException, fallback still used (operator must fix credentials and restart job)

### Fallback Behavior

When storage fails during `IssuanceService.issue()`:
- Non-blocking: log warning but continue
- Document is persisted with `stored_fallback=true` and data in BYTEA columns
- SyncJob will retry migration later
- Client receives 200 OK (emission succeeded)

When retrieval fails in `DocumentController`:
- If key exists but storage is unavailable: return 500 with message "Storage service unavailable"
- If key is null but BYTEA data exists: return 200 with BYTEA (fallback successful)
- If both missing: return 404

## Testing

**Unit Tests:**
- `StorageServiceTest`: Mock S3 client, verify put/get behavior
- `IssuanceServiceTest`: Inject mock StorageService, test fallback path
- `StorageSyncJobTest`: Verify fallback records are moved correctly

**Integration Tests:**
- `S3StorageServiceIntegrationTest`: Against localstack or moto (S3 mock)
- `StorageSyncJobIntegrationTest`: Real database + mock storage

**Fallback Scenario Tests:**
- Inject deliberately-failing StorageService, verify BYTEA fallback
- Verify Document.stored_fallback flag is set
- Verify SyncJob processes these records

## Dependencies

Already present in `pom.xml`:
- `software.amazon.awssdk:s3` (v2.31.6)
- Spring Boot 3.4.3

No new dependencies needed.

## Implementation Order

1. Create `StorageService` interface
2. Implement `S3StorageService` and `LocalStorageService`
3. Create storage config and Spring bean factory
4. Integrate into `IssuanceService`
5. Integrate into `DocumentController`
6. Create `StorageSyncJob` and scheduler
7. Add `stored_fallback` column migration
8. Unit + integration tests
9. Manual verification with real S3/R2

## Backwards Compatibility

- Existing documents with `xml_content`/`pdf_content` populated remain readable
- Downloads from Document.pdf_content (Phase C) will use `pdfContent` if `pdfKey` is null
- No breaking changes to API contracts
- Future data lives in S3/R2; historical data stays in BYTEA until SyncJob migrates it (or forever if sync is disabled)

## Success Criteria

1. ✅ `StorageService` abstraction in place, both S3 and local implementations working
2. ✅ `IssuanceService` integrates storage with fallback to BYTEA on failure
3. ✅ `DocumentController` can retrieve from both storage and BYTEA
4. ✅ `StorageSyncJob` successfully migrates fallback records to S3
5. ✅ Unit and integration tests pass
6. ✅ Manual verification: emit a document, verify it lands in S3 bucket, retrieve via API returns correct data
7. ✅ Failover scenario: disable S3, emit document, verify fallback to BYTEA works, re-enable S3, verify SyncJob migrates
