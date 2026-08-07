CREATE TABLE document (
    id                        TEXT PRIMARY KEY,
    emisor_id                 TEXT NOT NULL REFERENCES emisor(id),
    external_id               TEXT NOT NULL,
    tipo_dte                  INTEGER NOT NULL,
    folio                     INTEGER NOT NULL,
    rut_receptor              TEXT NOT NULL,
    razon_social_receptor     TEXT NOT NULL,
    monto_neto                INTEGER NOT NULL,
    monto_iva                 INTEGER NOT NULL,
    monto_total               INTEGER NOT NULL,
    estado                    TEXT NOT NULL,
    track_id                  TEXT,
    xml_key                   TEXT,
    pdf_key                   TEXT,
    sii_estado_detalle        TEXT,
    documento_referenciado_id TEXT REFERENCES document(id),
    intentos_consulta         INTEGER NOT NULL DEFAULT 0,
    proxima_consulta_at       TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_folio_unico UNIQUE (emisor_id, tipo_dte, folio),
    CONSTRAINT document_external_unico UNIQUE (emisor_id, external_id)
);

CREATE INDEX idx_document_pendientes
    ON document(proxima_consulta_at)
    WHERE estado IN ('PENDIENTE_ENVIO', 'ENVIADO', 'ERROR_ENVIO');
