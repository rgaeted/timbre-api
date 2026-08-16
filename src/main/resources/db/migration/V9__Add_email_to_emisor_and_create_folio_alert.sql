-- Add email column to emisor table
ALTER TABLE emisor ADD COLUMN email VARCHAR(255);

-- Create folio_alert table for tracking last alert sent per emisor/tipoDte
CREATE TABLE folio_alert (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    emisor_id VARCHAR(255) NOT NULL,
    tipo_dte INTEGER NOT NULL,
    last_alert_sent_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(emisor_id, tipo_dte),
    FOREIGN KEY(emisor_id) REFERENCES emisor(id) ON DELETE CASCADE
);

CREATE INDEX idx_folio_alert_emisor_tipo ON folio_alert(emisor_id, tipo_dte);
