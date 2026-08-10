# Diseño: Envío de DTE al SII (fase B1)

Fecha: 2026-08-10

## Contexto

Es la fase B del roadmap (`docs/ROADMAP.md`), dividida en dos sub-proyectos
independientes:

- **B1 — Autenticación + envío** (este documento): sube el `EnvioDTE` que la fase A
  ya construye, firma y persiste (`Document.xml_content`, `estado=PENDIENTE_ENVIO`) al
  SII, y marca el resultado.
- **B2 — Consulta de estado** (siguiente, fuera de alcance aquí): job que pregunta al
  SII por `trackId` y mueve el documento a `ACEPTADO`/`RECHAZADO`/`ACEPTADO_CON_REPARO`.

**Fuera de alcance:** Boletas Electrónicas (39/41) — no se emiten hoy
(`CafService.TIPOS_SOPORTADOS` = {33, 61}) y, aunque se agregaran, no se envían
documento por documento como Factura/NC: usan un Resumen de Ventas Diarias (RVD)
periódico, un flujo distinto. Ver `docs/ROADMAP.md`, fase H.

**Riesgo asumido:** no hay WSDL ni documentación oficial del SII en este repo. Los
formatos exactos de los servicios de semilla/token/subida (`CrSeed.jws`,
`GetTokenFromSeed.jws`, `DTEUpload`) se implementan con el mejor conocimiento
disponible y se espera ajustarlos una vez que se pruebe contra el ambiente de
certificación real del SII — decisión tomada explícitamente con el usuario.

## Alcance de B1

Cada fila de `document` con `estado=PENDIENTE_ENVIO`, o con `estado=ERROR_ENVIO` y
`xml_content` no nulo (falló un envío anterior, pero el XML firmado sigue siendo
válido — no hace falta re-emitir), se sube al SII. El resultado deja el documento en
`ENVIADO` (con `trackId`) o de vuelta en `ERROR_ENVIO` (con el detalle del fallo).

Los `ERROR_ENVIO` de la fase A (`xml_content IS NULL` — falló el armado/firma antes
de completarse) **no** son tocados por este job: no hay nada que subir, y ya quedan
bloqueados para reintento por `IssuanceService` (409 `emision_previa_fallida`).

## Arquitectura

Un job programado (`@Scheduled`, la app ya tiene `@EnableScheduling`) que:

1. Busca candidatos vía `documentRepository.findByEstadoInAndProximaConsultaAtBefore(List.of(PENDIENTE_ENVIO, ERROR_ENVIO), Instant.now())`
   (método ya existente) y descarta en memoria los `ERROR_ENVIO` con `xmlContent == null`.
2. Agrupa el resto por `emisorId` (la semilla/token se autentican con el certificado
   de cada emisor).
3. Por emisor: pide una semilla, la firma con el certificado (mismo
   `CertificateProvider` que usa `IssuanceService`), la canjea por un token — un
   único ciclo semilla+token por emisor por corrida del job, sin cachear entre
   corridas.
4. Con ese token, sube cada documento del emisor al endpoint de subida.
5. Actualiza el resultado por documento (ver "Manejo de errores").

## Componentes nuevos

**Paquete `cl.timbre.sii`:**

- `SiiAuthClient` — arma a mano (DOM, sin JAXB/librería SOAP — misma filosofía que
  `DteXmlBuilder`/`TedBuilder`/`EnvioDteBuilder`) el sobre XML de `CrSeed`, extrae la
  semilla de la respuesta, arma el XML de la semilla firmada con
  `CertificateProvider.forEmisor(emisor)`, y lo canjea por un token en
  `GetTokenFromSeed`.
- `SiiUploadClient` — sube un `xml_content` ya firmado como `multipart/form-data` al
  endpoint `DTEUpload` con el token en el header, parsea la respuesta y devuelve el
  `trackId` (éxito) o un código/mensaje de rechazo (falla definitiva).
- Ambos usan `RestClient` (incluido en `spring-boot-starter-web`, sin dependencia
  nueva) en vez de `RestTemplate`/`WebClient`.
- Resolución de URL: nueva property `sii.base-url` (vacía por defecto). Si está
  seteada, pisa la URL base para todas las llamadas SII — permite apuntar los tests a
  un `MockWebServer` local. Si no, se usa `emisor.getAmbiente().baseUrl()` (ya
  existente en el enum `Ambiente`).

**`cl.timbre.sii.EnvioSiiJob`**: el `@Scheduled` descrito en Arquitectura, orquesta
`SiiAuthClient` + `SiiUploadClient` por emisor y actualiza `Document` vía
`DocumentRepository`.

## Manejo de errores y backoff

- `sii.envio-max-intentos` (config, default 10) y un backoff fijo configurable entre
  corridas del job (no exponencial — no vale la complejidad para un job batch de bajo
  volumen).
- **Falla transitoria** (timeout, SII caído, error de red): incrementa
  `intentosConsulta`, reprograma `proximaConsultaAt` = ahora + backoff, se queda en
  `ERROR_ENVIO`. Si `intentosConsulta` alcanza el máximo, deja de reintentarse solo
  (sigue en `ERROR_ENVIO`, requiere intervención manual).
- **Rechazo definitivo del SII** (la subida llegó pero el SII la rechaza por datos
  inválidos — no cambiaría al reintentar): se fija `intentosConsulta` directamente al
  máximo (así el job deja de recogerlo en la próxima corrida) y se deja el detalle en
  `sii_estado_detalle`. No se agrega un estado nuevo a `DocumentStatus` para esto.
- **Éxito**: `estado=ENVIADO`, guarda `trackId`, resetea `intentosConsulta=0`, fija
  `proximaConsultaAt` = ahora + delay de consulta (para que B2 lo recoja).
- **Riesgo aceptado, no resuelto de raíz:** si el job sube exitosamente al SII pero
  falla antes de persistir `estado=ENVIADO`, la próxima corrida reintenta y reenvía el
  mismo documento (folio duplicado del lado del SII). No hay forma barata de
  exactamente-una-vez sin soporte de idempotencia del SII; se documenta como
  limitación conocida, misma filosofía que el folio quemado ya aceptado en la fase A.
- Logging vía SLF4J (mismo patrón introducido en `IssuanceService` durante el review
  final de la fase A — sigue siendo el único lugar del proyecto con logging).

## Testing

- `SiiAuthClientTest` / `SiiUploadClientTest`: unitarios contra `MockWebServer` (ya en
  `pom.xml`, sin usar hasta ahora) — éxito, rechazo, timeout.
- `EnvioSiiJobTest`: integración completa (`AbstractIntegrationTest`, Postgres real
  vía Testcontainers) con `MockWebServer` haciendo de SII, usando `sii.base-url` para
  apuntar el cliente al mock en vez de a `maullin.sii.cl`/`palena.sii.cl`.
- Casos a cubrir: envío exitoso (PENDIENTE_ENVIO → ENVIADO con trackId), falla
  transitoria (reintenta, cuenta el intento), rechazo definitivo (dejar de
  reintentar), máximo de intentos alcanzado, reintento de un `ERROR_ENVIO` con XML
  existente, y que un `ERROR_ENVIO` con `xml_content` nulo (de la fase A) nunca se
  toca.
