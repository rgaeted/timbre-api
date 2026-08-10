# Roadmap de timbre-api

El plan de implementación original se perdió (nunca quedó commiteado). Este roadmap
se reconstruyó el 2026-08-09 a partir de evidencia en el repo — dependencias del
`pom.xml` sin usar, bloques de configuración en `application.yml` sin leer, y
columnas/índices de la base de datos sin ningún código que los llene — y el orden
de fases fue confirmado por el equipo. Actualízalo a medida que cada fase se
complete.

## A. `IssuanceService` + `POST /api/v1/documents` — ✅ Completo (2026-08-10)

Arma, timbra, firma y persiste una Factura (33) o Nota de Crédito (61). Orquesta
`TotalsCalculator` → `FolioAssigner` → `DteXmlBuilder` → `TedBuilder` → `XmlSigner`
→ `EnvioDteBuilder` y persiste el resultado como `Document` con estado
`PENDIENTE_ENVIO`. **No envía el documento al SII** — eso es la fase B.

Decisión interina vigente: el XML firmado se guarda en la columna `document.xml_content`
en vez de storage externo. La fase D reemplaza esto por `xml_key` + S3/R2.

Deuda técnica menor detectada en el review final, no bloqueante:
- `{33, 61}` (tipos de documento soportados) está declarado por separado en
  `CafService` e `IssuanceService`.
- `DocumentResponse` unbox-ea campos `Integer` de `Document` a `int` primitivo —
  seguro hoy (`IssuanceService` es el único que escribe `Document` y siempre llena
  esos campos), pero sería un NPE latente si alguna fase futura persistiera un
  `Document` parcial.
- `Document.save()` siempre hace `merge()` en vez de `persist()` (tiene `@Id` de
  tipo `String` asignado a mano, sin `@Version`) — un round-trip extra por escritura,
  patrón preexistente en todo el repo.

## B. Envío al SII

Autenticación (`GetSeed`/`GetToken` firmado con el certificado digital), subida del
`EnvioDTE` ya armado por la fase A, y un job programado que consulta el estado del
envío por `trackId` y actualiza `Document.estado`/`sii_estado_detalle`.

Evidencia de que estaba planeado: `Document.trackId`, `intentosConsulta`,
`proximaConsultaAt`, el índice `idx_document_pendientes` (hecho a medida para esa
consulta), `sii.timeout-ms` sin usar, y la dependencia de test `mockwebserver` (para
simular el SII sin llamarlo de verdad).

## C. Representación gráfica (RIDE / PDF con timbre visual)

Código de barras PDF417 a partir del `<TED>` (dependencia `zxing`, sin usar) y PDF
completo del documento (`openpdf`, sin usar). Llena `Document.pdf_key`.

## D. Storage (S3/R2)

El SDK de AWS (`software.amazon.awssdk:s3`) y el bloque `storage.*` completo en
`application.yml` (provider, bucket, endpoint R2, credenciales) están scaffoldeados
sin ningún código que los lea. Acá se reemplaza la columna `xml_content` de la fase A
por `xml_key`/`pdf_key` reales.

## E. Alertas de folios bajos por email

`spring-boot-starter-mail` + `timbre.folio-alert-threshold`/`admin-email`/`mail-from`
existen en la config sin usar. `FolioAssigner.disponibles()` ya está construido (solo
se usa en tests hoy) y es exactamente el dato que este job necesitaría.

## F. Endpoints de administración

La capa de servicio ya existe pero sin controllers HTTP:
- Alta de emisor (`EmisorRepository`)
- Generar/revocar API keys (`ApiKeyService.generate`/`revoke`)
- Subida de CAF vía multipart (`CafService.register` espera `byte[]`;
  `servlet.multipart.max-file-size: 2MB` está configurado pero nada lo invoca)
- Consulta de folios disponibles

## G. Posible gap — no agendado aún

`CertificateProvider` lee el certificado de una única variable de entorno fija
(`sii.cert-p12-base64`), no por emisor. `Emisor.certEnvVar` existe precisamente para
esto (lookup del certificado por emisor, multi-tenant) pero no se usa en ningún lado.
Solo importa cuando haya más de un emisor con certificados distintos.

## H. Boletas Electrónicas — fuera de alcance, no agendado

Hoy el sistema solo soporta Factura (33) y Nota de Crédito (61) —
`CafService.TIPOS_SOPORTADOS` e `IssuanceService` tienen `{33, 61}` fijo en el
código. Boleta (39) y Boleta Exenta (41) no se pueden emitir todavía.

Nota de dominio para quien retome esto: las boletas **no** se envían al SII
documento por documento como Factura/NC (el mecanismo de la fase B1). Se reportan
mediante un **Resumen de Ventas Diarias (RVD / Libro de Boletas)**, un envío
periódico (típicamente diario) con formato y endpoint propios. No generalizar el
envío por documento de B1 para boletas — es un flujo genuinamente distinto, sería
la abstracción equivocada. Si se agregan boletas más adelante, es una fase nueva
(emisión + job de RVD diario), no una extensión de B.
