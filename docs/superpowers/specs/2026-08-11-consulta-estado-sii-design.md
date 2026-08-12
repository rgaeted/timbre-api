# Diseño: Consulta de estado de DTE al SII (fase B2)

Fecha: 2026-08-11

## Contexto

Es la fase B del roadmap (`docs/ROADMAP.md`), segundo y último sub-proyecto:

- **B1 — Autenticación + envío** (completo, 2026-08-11): sube el `EnvioDTE` al SII y
  deja el documento en `ENVIADO` con un `trackId`.
- **B2 — Consulta de estado** (este documento): pregunta al SII por ese `trackId` y
  mueve el documento a `ACEPTADO`, `RECHAZADO`, o lo deja reintentando si el SII
  todavía no termina de procesarlo.

**Riesgo asumido, igual que B1:** no hay WSDL ni documentación oficial del SII en
este repo. El formato del servicio de consulta (`QueryEstUp.jws`) y los códigos de
estado que devuelve se implementan con el mejor conocimiento disponible, a ajustar
contra el ambiente de certificación real — misma decisión que se tomó explícitamente
para B1, aplicada aquí de nuevo.

**Fuera de alcance:** distinguir `ACEPTADO_CON_REPARO` de `ACEPTADO`. No hay un
código confiable identificado para "aceptado con reparos" sin el WSDL real; según la
política de "código no reconocido → reintentar" (ver más abajo), cualquier respuesta
que no sea explícitamente un aceptado o rechazo limpio cae en reintento, no en un
estado equivocado. Cuando la verificación manual (ya pendiente para B1) muestre el
código real de reparos, se agrega esa clasificación.

## Alcance de B2

Cada `Document` con `estado=ENVIADO` y `proximaConsultaAt` vencido se consulta contra
el SII usando su `trackId`. El resultado:

- **Estado terminal reconocido** (aceptado o rechazado): se fija `estado` acorde,
  `proximaConsultaAt=null` (no se vuelve a consultar), se guarda el detalle del SII.
- **Todavía procesando, código no reconocido, o cualquier error de comunicación:**
  se reintenta con backoff hasta un máximo configurable de intentos. Al agotarse,
  el documento se queda en `ENVIADO` con `proximaConsultaAt=null` — deja de
  consultarse solo, requiere revisión manual. Nunca se marca `RECHAZADO` ni
  `ACEPTADO` ante la duda.

## Arquitectura

Un segundo job programado, con el mismo patrón job/scheduler separado que B1 (para
poder desactivar el disparo automático en tests sin perder la capacidad de invocar
la lógica directamente):

1. `ConsultaEstadoSiiJob.consultarEnviados()` busca candidatos vía
   `documentRepository.findByEstadoInAndProximaConsultaAtBefore(List.of(ENVIADO), Instant.now(), pageable)`
   (mismo método paginado que ya usa `EnvioSiiJob`, sin filtro adicional en memoria —
   todo documento `ENVIADO` ya tiene `trackId` asignado por B1).
2. Agrupa por emisor, obtiene token con `SiiAuthClient` (reutilizado tal cual, sin
   cambios). Falla de autenticación: se reprograma el lote sin cargarle intento a
   ningún documento (mismo fix ya aplicado en B1 para el mismo caso).
3. Por documento: consulta el `trackId` con `SiiConsultaClient`, actualiza el estado
   según el resultado.
4. `ConsultaEstadoSiiJobScheduler`: el `@Scheduled` real, condicionado a
   `sii.consulta-job-enabled` (default `true`, `false` en toda la suite de
   integración vía `AbstractIntegrationTest`).

## Componentes nuevos

**`cl.timbre.sii.SiiConsultaClient`:**

```java
public record ResultadoConsulta(EstadoSii estado, String detalle) {}
public enum EstadoSii { ACEPTADO, RECHAZADO, EN_PROCESO }
```

- `consultar(Emisor emisor, SiiAuthClient.Token token, String trackId)` → `ResultadoConsulta`.
- Llama a `/DTEWS/QueryEstUp.jws` con un sobre SOAP armado a mano, mismo estilo que
  `SiiAuthClient` (sin JAXB/librería SOAP).
- Extrae `<ESTADO>`/`<GLOSA>` de la respuesta con el mismo patrón de extracción por
  regex que `SiiAuthClient.extraerEtiqueta` (sin echo del cuerpo crudo en mensajes de
  error — mismo fix de seguridad aplicado en el review final de B1).
- Mapeo de código a `EstadoSii`, conservador:
  ```java
  case "EPR", "SOK" -> ACEPTADO;
  case "RCH", "RCT", "RFR", "RSC" -> RECHAZADO;
  default -> EN_PROCESO;
  ```

**`cl.timbre.sii.ConsultaEstadoSiiJob`** + **`ConsultaEstadoSiiJobScheduler`**: como se
describe en Arquitectura.

**Config nueva en `SiiProperties`** (se agregan al final del record, 3 campos
nuevos — hay que actualizar los `new SiiProperties(...)` posicionales que ya existen
en `SiiUrlResolverTest`, `SiiAuthClientTest` y `SiiUploadClientTest`):
`consultaMaxIntentos`, `consultaBackoffMs`, `consultaJobFixedDelayMs`.

## Manejo de errores y backoff

- `sii.consulta-max-intentos` / `sii.consulta-backoff-ms`: config propia, separada de
  `sii.envio-max-intentos`/`envio-backoff-ms` — consultar estado y subir un documento
  son operaciones distintas con cadencias razonablemente distintas.
- Falla transitoria (error de red, código no reconocido, "todavía procesando"):
  incrementa `intentosConsulta`, reprograma `proximaConsultaAt` con backoff. Al
  agotar el máximo, dejar de reintentar solo (`proximaConsultaAt=null`, sigue
  `ENVIADO`).
- Falla de autenticación del emisor: no carga intento a ningún documento del lote
  (mismo fix que B1), solo reprograma.
- Nunca marcar `ACEPTADO` o `RECHAZADO` ante una respuesta ambigua o no reconocida —
  ver "Fuera de alcance" arriba.

## Testing

- `SiiConsultaClientTest`: unitario contra `MockWebServer` — aceptado, rechazado,
  código no reconocido, falla de red.
- `ConsultaEstadoSiiJobTest`: integración completa (Postgres real + `MockWebServer`),
  escenarios espejando los de `EnvioSiiJobTest`: aceptado (termina), rechazado
  (termina), en-proceso (reintenta), código desconocido (reintenta igual que
  en-proceso), máximo de intentos alcanzado, falla de autenticación (no carga
  intento).
