ALTER TABLE emisor ADD COLUMN rut_envia TEXT;
UPDATE emisor SET rut_envia = rut WHERE rut_envia IS NULL;
ALTER TABLE emisor ALTER COLUMN rut_envia SET NOT NULL;
