# RIDE / PDF con timbre visual — Diseño (Fase C)

## Contexto

Fase C del roadmap (`docs/ROADMAP.md`). Las fases A y B (emisión + envío al SII +
consulta de estado) ya están completas. Hoy `Document` persiste el XML firmado
(`xml_content`) pero no genera ninguna representación gráfica del documento
(RIDE). Las dependencias `com.google.zxing:core`/`javase` y `com.github.librepdf:openpdf`
ya están en `pom.xml` sin usar, y la columna `Document.pdf_key` existe sin
ningún código que la llene — evidencia de que esta fase estaba planeada.

Igual que en la fase A con `xml_content`, la fase D (storage S3/R2) todavía no
existe, así que esta fase toma la misma decisión interina: persistir los bytes
del PDF directo en una columna de la base de datos, a reemplazar por `pdf_key`
+ storage real cuando llegue la fase D.

## Alcance

Generar el RIDE (Representación Impresa del Documento Electrónico) para
Factura Electrónica (33) y Nota de Crédito (61) — los únicos tipos soportados
hoy — como PDF tamaño carta, con el timbre electrónico (PDF417 del `<TED>`)
en el pie de página. Fuera de alcance: boletas (fuera de alcance del sistema
completo, ver fase H del roadmap), storage externo (fase D), reintento
automático si falla la generación.

## Arquitectura

Paquete nuevo `cl.timbre.pdf`:

- **`RideBuilder.build(DteDocument dte, Element ted, Emisor emisor, LocalDateTime timestamp) -> byte[]`**
  Arma el PDF con openpdf (header, receptor, tabla de detalle, referencias,
  totales, footer) y codifica el `<TED>` serializado como PDF417 vía zxing,
  embebido como imagen en el footer.

`IssuanceService.issue()` llama a `RideBuilder.build(...)` inmediatamente
después de construir el sobre firmado (`construirSobreFirmado`), reutilizando
el `DteDocument` y el `Element ted` que ya arma internamente ese método — hay
que exponerlos como resultado en vez de dejarlos encapsulados. La llamada a
`RideBuilder` va en su **propio try/catch**, separado del que protege la
construcción/firma del XML: si el PDF falla, se loguea el error (mismo patrón
de `mensajeTruncado` que ya usa el manejo de errores de emisión) y el
documento se persiste igual, con `estado = PENDIENTE_ENVIO`, `xmlContent`
presente y `pdfContent = null`. La emisión real — lo que efectivamente le
importa al SII — nunca se bloquea por un fallo en la generación del RIDE.

No hay mecanismo de reintento automático para un PDF que falló: queda como
deuda técnica documentada (mismo criterio que los ítems menores dejados en las
fases A/B1), a resolver manualmente o en una fase futura si llega a ocurrir en
la práctica.

## Storage y persistencia

Migración `V7__document_pdf_content.sql`:

```sql
ALTER TABLE document ADD COLUMN pdf_content BYTEA;
```

`Document.pdfContent` (`byte[]`), análogo a `xmlContent`. Cuando llegue la
fase D, se reemplaza por `pdf_key` + S3/R2, igual que está planeado para el
XML.

## Layout del RIDE (carta completa)

Aprobado por mockup visual durante el brainstorming. Estructura:

1. **Header**: datos del emisor (razón social, RUT, giro, dirección) a la
   izquierda; recuadro con borde rojo mostrando "R.U.T.: {rut emisor}",
   "{TIPO DOCUMENTO} ELECTRÓNICA", "N° {folio}" a la derecha.
2. **Receptor**: razón social, RUT, giro, dirección, fecha de emisión.
3. **Detalle**: tabla (cantidad, descripción, precio unitario, monto) con
   `PdfPTable` de openpdf. **Multi-página**: si las líneas no caben en una
   página, el PDF continúa en páginas adicionales repitiendo el header de la
   tabla — openpdf soporta esto de forma nativa.
4. **Referencias** (solo Nota de Crédito, tipoDte 61): tipo de documento
   referenciado, folio, fecha, razón — desde `DteReference`.
5. **Totales**: neto, IVA, total.
6. **Footer**: código de barras PDF417 (zxing) del `<TED>` serializado en
   bytes ISO-8859-1 (mismo encoding que usa `TedBuilder` para firmar), más la
   leyenda "Timbre Electrónico SII" y el texto de resolución
   (`Emisor.resolucionNumero` / `resolucionFecha`).

## Endpoint

`GET /api/v1/documents/{id}/pdf` en `DocumentController`:

- Resuelve con `documentRepository.findByIdAndEmisorId(id, emisor.getId())`
  (mismo scoping por emisor que ya usa el resto del sistema).
- 404 si el documento no existe para ese emisor.
- 409 (`pdf_no_disponible`) si el documento existe pero `pdfContent` es null
  (falló la generación y no hay reintento automático).
- 200 con `Content-Type: application/pdf` y los bytes crudos.

## Testing

- **`RideBuilderTest`** (unitario): fixture de `DteDocument` + `TED`, valida
  PDF no vacío y parseable (`PdfReader`); caso con muchas líneas valida
  2+ páginas; caso Nota de Crédito valida que aparece la sección de
  referencias.
- **Round-trip PDF417**: decodifica con zxing el barcode embebido y confirma
  que el texto recuperado coincide con el `<TED>` serializado — única forma
  de verificar el timbre visual sin inspección manual.
- **`IssuanceServiceTest`**: caso donde `RideBuilder` lanza excepción —
  confirma que el documento igual queda `PENDIENTE_ENVIO` con `xmlContent`
  presente y `pdfContent` null.
- **`DocumentControllerTest`**: 200 + `Content-Type: application/pdf` cuando
  existe; 404 si el documento es de otro emisor; 409 si `pdfContent` es null.
