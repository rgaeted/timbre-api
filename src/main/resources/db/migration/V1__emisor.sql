CREATE TABLE emisor (
    id                 TEXT PRIMARY KEY,
    rut                TEXT NOT NULL UNIQUE,
    razon_social       TEXT NOT NULL,
    giro               TEXT NOT NULL,
    acteco             INTEGER NOT NULL,
    direccion_origen   TEXT NOT NULL,
    comuna_origen      TEXT NOT NULL,
    resolucion_numero  INTEGER NOT NULL,
    resolucion_fecha   DATE NOT NULL,
    ambiente           TEXT NOT NULL,
    cert_env_var       TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
