CREATE TABLE api_key (
    id           TEXT PRIMARY KEY,
    emisor_id    TEXT NOT NULL REFERENCES emisor(id),
    nombre       TEXT NOT NULL,
    prefijo      TEXT NOT NULL,
    hash         TEXT NOT NULL UNIQUE,
    revocada_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_key_emisor ON api_key(emisor_id);
