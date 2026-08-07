CREATE TABLE folio_range (
    id                  TEXT PRIMARY KEY,
    emisor_id           TEXT NOT NULL REFERENCES emisor(id),
    tipo_dte            INTEGER NOT NULL,
    folio_desde         INTEGER NOT NULL,
    folio_hasta         INTEGER NOT NULL,
    folio_actual        INTEGER NOT NULL,
    caf_xml             TEXT NOT NULL,
    private_key_pem     TEXT NOT NULL,
    fecha_autorizacion  DATE NOT NULL,
    agotado             BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT folio_range_rango_valido CHECK (folio_desde <= folio_hasta),
    CONSTRAINT folio_range_actual_en_rango
        CHECK (folio_actual >= folio_desde - 1 AND folio_actual <= folio_hasta),
    CONSTRAINT folio_range_unico UNIQUE (emisor_id, tipo_dte, folio_desde)
);

-- Índice del camino caliente: buscar el rango no agotado más antiguo de un tipo.
CREATE INDEX idx_folio_range_disponible
    ON folio_range(emisor_id, tipo_dte, fecha_autorizacion)
    WHERE agotado = FALSE;
