# Diseño: IssuanceService y endpoint de emisión de DTE

Fecha: 2026-08-09

## Contexto

El plan de implementación original de timbre-api se perdió (no quedó commiteado, y no hay
transcripción de sesión previa que lo recupere). Se reconstruyó un roadmap de 7 fases a partir
de evidencia en el repo (dependencias del `pom.xml`, config sin usar en `application.yml`,
columnas e índices de la base de datos sin uso) y el usuario confirmó el orden:

- **A. `IssuanceService` + endpoint de emisión** ← este documento
- B. Envío al SII (autenticación, upload, consulta de estado)
- C. Representación gráfica del documento (RIDE/PDF con timbre visual)
- D. Storage (S3/R2) para XML y PDF firmados
- E. Alertas de folios bajos por email
- F. Endpoints de administración (emisor, API keys, subida de CAF)
- G. Posible gap: `CertificateProvider` no usa `Emisor.certEnvVar` (multi-tenant)

Este documento cubre solo la fase A: el primer endpoint que efectivamente emite un DTE.

## Alcance de la fase A

El endpoint arma, timbra y firma el DTE, y lo deja persistido. **No** lo envía al SII — eso es
la fase B. El resultado es un `EnvioDTE` firmado, listo para que una fase posterior lo despache.

Tipos de documento soportados: Factura (33) y Nota de Crédito (61) — el mismo universo que ya
soporta `CafService`.

## Contrato del endpoint

`POST /api/v1/documentos`

Autenticado vía `ApiKeyFilter` / `EmisorContext.current()`, igual que el resto de la API.

### Request — `IssueDocumentRequest`

```json
{
  "externalId": "pedido-8842",
  "tipoDte": 33,
  "fechaEmision": "2026-08-09",
  "receptor": {
    "rut": "77777777-7",
    "razonSocial": "Constructora Andes SpA",
    "giro": "Construccion",
    "direccion": "Av. Apoquindo 4500",
    "comuna": "Las Condes"
  },
  "lineas": [
    { "descripcion": "Generador", "cantidad": 1, "precioUnitarioBruto": 1190000, "tipo": "AFECTO" }
  ],
  "referencias": []
}
```

Reglas de validación:
- `externalId`: obligatorio, no vacío. Es la clave de idempotencia.
- `tipoDte`: debe ser 33 o 61 (400 si no).
- `fechaEmision`: obligatoria, no puede ser posterior a hoy.
- `receptor`: los 5 campos obligatorios, mismos límites de largo que ya aplica `DteXmlBuilder`
  (se truncan ahí, no hace falta duplicar la validación acá).
- `lineas`: reutiliza `IssueLine` (ya existe), al menos una línea.
- `referencias`: reutiliza forma de `DteReference`. Opcional para tipoDte 33. **Obligatoria y no
  vacía para tipoDte 61** (una nota de crédito siempre corrige un documento anterior — regla real
  del SII, se valida en el servicio).

### Response — `DocumentResponse` (200)

```json
{
  "id": "...",
  "externalId": "pedido-8842",
  "tipoDte": 33,
  "folio": 1042,
  "estado": "PENDIENTE_ENVIO",
  "montoNeto": 1000000,
  "montoIva": 190000,
  "montoTotal": 1190000,
  "xmlBase64": "..."
}
```

`xmlBase64` es el `EnvioDTE` firmado completo (el mismo formato que produce hoy
`EnvioDteBuilder`), codificado en base64.

## Orquestación (`IssuanceService`)

Nombre tomado del comentario ya existente en `DteXmlBuilder.java:52`, que anticipa esta pieza.

1. **Idempotencia**: `documentRepository.findByEmisorIdAndExternalId(emisorId, externalId)`. Si
   existe, se devuelve ese `Document` tal cual — no se toca ningún folio.
2. **Cálculo de totales**: `TotalsCalculator.compute(lineas)`. Corre *antes* de pedir folio, así
   un error de validación de montos (p.ej. total ≤ 0) no consume nada.
3. **Validación de referencias**: si `tipoDte == 61` y `referencias` está vacío → 400.
4. **Asignación de folio**: `FolioAssigner.assign(emisorId, tipoDte)`. Corre en su propia
   transacción (`REQUIRES_NEW`, ya implementado) y queda confirmada de inmediato. A partir de acá
   el folio está consumido pase lo que pase después.
5. **Construcción y firma**:
   - `DteDocument` a partir de emisor + receptor + folio + totales + referencias.
   - `DteXmlBuilder.build(dte)` → DOM del `<Documento>`.
   - `TedBuilder.build(...)` con la llave privada del CAF (`FolioRange.privateKeyPem`) → se
     inserta `<TED>`.
   - Se inserta `<TmstFirma>`.
   - `XmlSigner.sign(...)` con el `SigningMaterial` de `CertificateProvider.forEmisor(emisor)`.
   - `EnvioDteBuilder.build(emisor, List.of(documentoFirmado), material, timestamp)` → sobre
     final (`byte[]`).
6. **Persistencia (éxito)**: se guarda `Document` con `estado = PENDIENTE_ENVIO`, folio, datos del
   receptor, montos, y el XML del sobre.
7. **Persistencia (error)**: si algo fallara entre los pasos 4 y 6, se persiste igual un
   `Document` con `estado = ERROR_ENVIO`, el folio ya consumido, receptor y montos (ya calculados
   en el paso 2), `xml_content = null` y el detalle del error en `sii_estado_detalle`. Nunca se
   pierde el rastro de un folio ya tomado. Luego se relanza como
   `ApiException(500, "error_emision", ...)`.
8. **Carrera de duplicados**: el `UNIQUE(emisor_id, external_id)` ya existe en la tabla. Si dos
   requests con el mismo `externalId` llegan a la vez, el segundo `save()` choca contra esa
   restricción; se captura `DataIntegrityViolationException` y se relee/devuelve el documento que
   ganó la carrera en vez de propagar un 500.

## Persistencia del XML: decisión interina

La fase D (storage S3/R2) todavía no existe — el SDK de AWS y el bloque `storage.*` están en el
proyecto pero nada los usa. Para no bloquear la fase A en la D, el XML firmado se guarda en una
columna nueva `xml_content TEXT` en `document` (nullable). Cuando se implemente la fase D, esa
columna se reemplaza por subir el XML a storage y guardar la key en `xml_key` (que ya existe).
Esto es explícitamente un paso intermedio, no la arquitectura final.

## Cambios a código existente

- **Migración** `V6__document_xml_content.sql`: agrega `xml_content TEXT` a `document`.
- **`CafParser`**: hoy `privateKey(Caf)` lee `caf.privateKeyPem()`. Como `FolioRange` solo guarda
  el PEM (no el `Caf` completo), se cambia a `privateKey(String pem)`, con
  `privateKey(Caf caf)` delegando a él. No cambia la lógica interna de parseo de la llave.

## Componentes nuevos

- DTOs en `cl.timbre.dto`: `IssueDocumentRequest`, `ReceptorRequest`, `ReferenciaRequest`,
  `DocumentResponse`.
- Paquete nuevo `cl.timbre.issue`: `IssuanceService`, `DocumentController`.

## Testing

- Test de integración de `IssuanceService` (extiende `AbstractIntegrationTest`, Postgres real vía
  Testcontainers): camino feliz para 33 y 61, idempotencia por `externalId`, sin folios
  disponibles (409, ya cubierto por `FolioAssigner`), NC sin `referencias` (400), carrera de
  `externalId` duplicado, y el caso `ERROR_ENVIO` (forzable con un `CertificateProvider` mal
  configurado, como ya hace `CertificateProviderTest`).
- Validar el XML persistido contra el XSD real (`EnvioDTE_v10.xsd`), igual que hace
  `EnvioDteBuilderTest`.
