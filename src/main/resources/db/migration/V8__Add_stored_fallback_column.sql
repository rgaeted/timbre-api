ALTER TABLE document ADD COLUMN stored_fallback BOOLEAN DEFAULT FALSE NOT NULL;
CREATE INDEX idx_document_stored_fallback ON document(stored_fallback) WHERE stored_fallback = true;
COMMENT ON COLUMN document.stored_fallback IS 'Flag for documents awaiting S3 migration from BYTEA fallback.';
