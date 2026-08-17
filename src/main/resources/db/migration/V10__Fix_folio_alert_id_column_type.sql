-- V9 created folio_alert.id as UUID, but the FolioAlert entity (and every other
-- entity in this codebase: emisor, folio_range, document, etc.) maps id as a
-- String populated with UUID.randomUUID().toString(), stored as VARCHAR/TEXT.
-- This mismatch made Hibernate's schema validation fail at startup:
--   "wrong column type encountered in column [id] in table [folio_alert];
--    found [uuid (Types#OTHER)], but expecting [varchar(255) (Types#VARCHAR)]"
-- Align folio_alert.id with the rest of the schema.
ALTER TABLE folio_alert ALTER COLUMN id DROP DEFAULT;
ALTER TABLE folio_alert ALTER COLUMN id TYPE VARCHAR(255) USING id::text;
