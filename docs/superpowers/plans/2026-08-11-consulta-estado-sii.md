# Consulta de estado de DTE al SII (fase B2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A scheduled job that queries the SII for the status of every `ENVIADO` document (using the `trackId` phase B1 left behind) and moves it to `ACEPTADO` or `RECHAZADO`, or retries with its own backoff if the SII is still processing or returns something we don't recognize.

**Architecture:** `ConsultaEstadoSiiJob` mirrors `EnvioSiiJob`'s shape exactly: query candidates via the existing paginated repository method, group by emisor, get a token via the already-built `SiiAuthClient`, then call the new `SiiConsultaClient` per document. A separate `ConsultaEstadoSiiJobScheduler` holds the `@Scheduled` trigger, same job/scheduler split as B1 for testability.

**Tech Stack:** Spring Boot 3.4.3, Java 21, `RestClient`, MockWebServer (same patterns already established in `SiiAuthClient`/`SiiUploadClient`).

## Global Constraints

- **No WSDL/documentación oficial del SII en el repo**, same as B1. `SiiConsultaClient`'s SOAP envelope and status-code mapping are best-effort, to be adjusted during the same manual verification pass already documented for B1.
- **Never mark `ACEPTADO` or `RECHAZADO` on an ambiguous or unrecognized response.** Only two SII status codes map to `ACEPTADO`, four to `RECHAZADO` (see Task 2) — everything else, including exceptions, falls through to a retry.
- **`ACEPTADO_CON_REPARO` is explicitly out of scope** — no confident code identified for it without the real WSDL. Do not add it to `SiiConsultaClient.EstadoSii` or attempt to guess a code for it.
- **`@ConditionalOnProperty` goes on `ConsultaEstadoSiiJobScheduler` only**, never on `ConsultaEstadoSiiJob` — same reasoning as B1: tests need to `@Autowired` the job directly.
- Auth failures reschedule the whole emisor's batch via `proximaConsultaAt` **without** incrementing `intentosConsulta` — same fix already applied to `EnvioSiiJob` in B1's final review.
- No `@Transactional` anywhere in `ConsultaEstadoSiiJob`.
- Tests: Spanish method names describing behavior, AssertJ assertions, integration tests extend `AbstractIntegrationTest` (real Postgres via Testcontainers), MockWebServer for HTTP-level tests using the `okhttp3.mockwebserver` package (4.12.0, setter-style API — not `mockwebserver3`).

---

## Task 1: `SiiProperties` extendida para consulta

**Files:**
- Modify: `src/main/java/cl/timbre/config/SiiProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/cl/timbre/sii/SiiUrlResolverTest.java`
- Modify: `src/test/java/cl/timbre/sii/SiiAuthClientTest.java`
- Modify: `src/test/java/cl/timbre/sii/SiiUploadClientTest.java`

**Interfaces:**
- Produces: `SiiProperties` gains three fields at the end — `int consultaMaxIntentos, long consultaBackoffMs, long consultaJobFixedDelayMs`. The record becomes a 12-arg positional constructor. Tasks 2 and 3 consume the three new fields; every existing direct `new SiiProperties(...)` call site in the test suite must be updated to 12 args or the whole module fails to compile.

- [ ] **Step 1: Confirm the current 9-arg call sites (RED by construction)**

The current `SiiProperties` record has 9 fields, and exactly four places construct it directly: two in `SiiUrlResolverTest.java` (lines 16-17 and 25-26), one in `SiiAuthClientTest.java` (lines 37-38), one in `SiiUploadClientTest.java` (lines 28-29). Each currently ends in `..., 10, 300000, 60000, 600000);`. After Step 2 changes the record to 12 fields, all four become compile errors — that's the expected RED state; there's no separate test to write first for a pure config extension.

Run: `./mvnw test -Dtest=SiiUrlResolverTest,SiiAuthClientTest,SiiUploadClientTest`
Expected (before Step 2): PASS — this just establishes the current baseline before you touch the record.

- [ ] **Step 2: Extend `SiiProperties`**

Replace `src/main/java/cl/timbre/config/SiiProperties.java`:

```java
package cl.timbre.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sii")
public record SiiProperties(
        String ambiente,
        String certP12Base64,
        String certPassword,
        int timeoutMs,
        String baseUrl,
        int envioMaxIntentos,
        long envioBackoffMs,
        long envioJobFixedDelayMs,
        long envioConsultaDelayMs,
        int consultaMaxIntentos,
        long consultaBackoffMs,
        long consultaJobFixedDelayMs
) {}
```

- [ ] **Step 3: Run the three test classes to confirm they now fail to compile**

Run: `./mvnw test -Dtest=SiiUrlResolverTest,SiiAuthClientTest,SiiUploadClientTest`
Expected: does not compile — four `new SiiProperties(...)` calls now pass only 9 of the required 12 arguments.

- [ ] **Step 4: Add the new config keys to `application.yml`**

Modify `src/main/resources/application.yml`, add these three lines to the existing `sii:` block (after `envio-job-enabled`):

```yaml
  consulta-max-intentos: ${SII_CONSULTA_MAX_INTENTOS:50}
  consulta-backoff-ms: ${SII_CONSULTA_BACKOFF_MS:900000}
  consulta-job-fixed-delay-ms: ${SII_CONSULTA_JOB_FIXED_DELAY_MS:60000}
  consulta-job-enabled: ${SII_CONSULTA_JOB_ENABLED:true}
```

(`consulta-job-enabled` is not a `SiiProperties` field — same pattern as `envio-job-enabled`, read directly via `@ConditionalOnProperty` in Task 3.)

- [ ] **Step 5: Fix the four call sites**

In `src/test/java/cl/timbre/sii/SiiUrlResolverTest.java`, both `new SiiProperties(...)` calls (lines 16-17 and 25-26) get `, 10, 300000, 60000` appended before the closing `);`. Example for the first:

```java
        SiiProperties properties = new SiiProperties(
                "CERTIFICACION", "", "", 5000, "", 10, 300000, 60000, 600000, 10, 300000, 60000);
```

and the second:

```java
        SiiProperties properties = new SiiProperties(
                "CERTIFICACION", "", "", 5000, "http://localhost:9999", 10, 300000, 60000, 600000, 10, 300000, 60000);
```

In `src/test/java/cl/timbre/sii/SiiAuthClientTest.java` (lines 37-38):

```java
        SiiProperties properties = new SiiProperties("CERTIFICACION", "", "", 5000,
                "http://localhost:" + server.getPort(), 10, 300000, 60000, 600000, 10, 300000, 60000);
```

In `src/test/java/cl/timbre/sii/SiiUploadClientTest.java` (lines 28-29):

```java
        SiiProperties properties = new SiiProperties("CERTIFICACION", "", "", 5000,
                "http://localhost:" + server.getPort(), 10, 300000, 60000, 600000, 10, 300000, 60000);
```

- [ ] **Step 6: Run the three test classes to confirm they pass again**

Run: `./mvnw test -Dtest=SiiUrlResolverTest,SiiAuthClientTest,SiiUploadClientTest`
Expected: PASS (all tests, unchanged behavior — only the positional arg count changed)

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: PASS — confirms no other file constructs `SiiProperties` directly.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/cl/timbre/config/SiiProperties.java src/main/resources/application.yml src/test/java/cl/timbre/sii/SiiUrlResolverTest.java src/test/java/cl/timbre/sii/SiiAuthClientTest.java src/test/java/cl/timbre/sii/SiiUploadClientTest.java
git commit -m "feat: config de reintentos para la consulta de estado al SII"
```

---

## Task 2: `SiiConsultaClient`

**Files:**
- Create: `src/main/java/cl/timbre/sii/SiiConsultaClient.java`
- Test: `src/test/java/cl/timbre/sii/SiiConsultaClientTest.java`

**Interfaces:**
- Consumes: `SiiUrlResolver.resolve(Emisor)`, `SiiProperties.timeoutMs()`, `RutValidator.body(String)`/`dv(String)`, `SiiAuthClient.Token`.
- Produces: `SiiConsultaClient.EstadoSii` (enum: `ACEPTADO`, `RECHAZADO`, `EN_PROCESO`), `SiiConsultaClient.ResultadoConsulta(EstadoSii estado, String detalle)` (record), `SiiConsultaClient.consultar(Emisor emisor, SiiAuthClient.Token token, String trackId)` → `ResultadoConsulta`. Task 3 consumes this method.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/cl/timbre/sii/SiiConsultaClientTest.java`:

```java
package cl.timbre.sii;

import cl.timbre.TestFixtures;
import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Emisor;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiiConsultaClientTest {

    private MockWebServer server;
    private SiiConsultaClient client;
    private Emisor emisor;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        SiiProperties properties = new SiiProperties("CERTIFICACION", "", "", 5000,
                "http://localhost:" + server.getPort(), 10, 300000, 60000, 600000, 10, 300000, 60000);

        client = new SiiConsultaClient(new SiiUrlResolver(properties), properties);
        emisor = TestFixtures.emisor();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void encolarRespuesta(String estado, String glosa) {
        server.enqueue(new MockResponse().setBody(
                "<SII:RESPUESTA xmlns:SII=\"http://www.sii.cl/XMLSchema\">"
                        + "<SII:RESP_BODY><ESTADO>" + estado + "</ESTADO><GLOSA>" + glosa + "</GLOSA></SII:RESP_BODY>"
                        + "</SII:RESPUESTA>"));
    }

    @Test
    void unEstadoAceptadoSeMapeaAAceptado() {
        encolarRespuesta("EPR", "Envio Procesado");

        SiiConsultaClient.ResultadoConsulta resultado =
                client.consultar(emisor, new SiiAuthClient.Token("token"), "12345");

        assertThat(resultado.estado()).isEqualTo(SiiConsultaClient.EstadoSii.ACEPTADO);
        assertThat(resultado.detalle()).isEqualTo("Envio Procesado");
    }

    @Test
    void unEstadoRechazadoSeMapeaARechazado() {
        encolarRespuesta("RCH", "Rechazado por error de firma");

        SiiConsultaClient.ResultadoConsulta resultado =
                client.consultar(emisor, new SiiAuthClient.Token("token"), "12345");

        assertThat(resultado.estado()).isEqualTo(SiiConsultaClient.EstadoSii.RECHAZADO);
    }

    @Test
    void unCodigoNoReconocidoSeMapeaAEnProceso() {
        encolarRespuesta("ZZZ", "Codigo nunca antes visto");

        SiiConsultaClient.ResultadoConsulta resultado =
                client.consultar(emisor, new SiiAuthClient.Token("token"), "12345");

        assertThat(resultado.estado()).isEqualTo(SiiConsultaClient.EstadoSii.EN_PROCESO);
    }

    @Test
    void elTrackIdYElTokenViajanEnElRequest() throws InterruptedException {
        encolarRespuesta("EPR", "ok");

        client.consultar(emisor, new SiiAuthClient.Token("mi-token"), "99999999");

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("99999999");
        assertThat(body).contains("mi-token");
    }

    @Test
    void unaRespuestaSinEstadoFalla() {
        server.enqueue(new MockResponse().setBody("<SII:RESPUESTA/>"));

        assertThatThrownBy(() -> client.consultar(emisor, new SiiAuthClient.Token("token"), "12345"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ESTADO");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=SiiConsultaClientTest`
Expected: does not compile — `SiiConsultaClient` doesn't exist yet.

- [ ] **Step 3: Implement `SiiConsultaClient`**

Create `src/main/java/cl/timbre/sii/SiiConsultaClient.java`:

```java
package cl.timbre.sii;

import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Emisor;
import cl.timbre.rut.RutValidator;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consulta el estado de un envio ya subido al SII por su trackId. Servicio legacy
 * SOAP (QueryEstUp.jws) sin WSDL disponible en este repo -- mismo riesgo que
 * SiiAuthClient: mejor conocimiento disponible, a ajustar contra el SII real.
 */
@Component
public class SiiConsultaClient {

    public enum EstadoSii { ACEPTADO, RECHAZADO, EN_PROCESO }

    public record ResultadoConsulta(EstadoSii estado, String detalle) {}

    private final SiiUrlResolver urlResolver;
    private final RestClient restClient;

    public SiiConsultaClient(SiiUrlResolver urlResolver, SiiProperties properties) {
        this.urlResolver = urlResolver;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeoutMs());
        requestFactory.setReadTimeout(properties.timeoutMs());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public ResultadoConsulta consultar(Emisor emisor, SiiAuthClient.Token token, String trackId) {
        String url = urlResolver.resolve(emisor) + "/DTEWS/QueryEstUp.jws";
        String sobre = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><getEstUp>"
                + "<RutCompania>" + RutValidator.body(emisor.getRut()) + "</RutCompania>"
                + "<DvCompania>" + RutValidator.dv(emisor.getRut()) + "</DvCompania>"
                + "<Token>" + token.valor() + "</Token>"
                + "<TrackId>" + trackId + "</TrackId>"
                + "</getEstUp></soapenv:Body></soapenv:Envelope>";

        String respuesta = restClient.post()
                .uri(url)
                .contentType(MediaType.TEXT_XML)
                .body(sobre)
                .retrieve()
                .body(String.class);

        String codigo = extraerEtiqueta(respuesta, "ESTADO");
        String glosa = extraerEtiquetaOpcional(respuesta, "GLOSA");
        return new ResultadoConsulta(mapearEstado(codigo), glosa);
    }

    /**
     * Sin WSDL del SII, no se conoce el universo completo de codigos de ESTADO.
     * Solo se reconocen explicitamente los que hay razonable confianza en su
     * significado; todo lo demas (incluyendo "aceptado con reparos", para el que
     * no hay codigo identificado) cae en EN_PROCESO, que el job reintenta -- nunca
     * se asume un resultado terminal ante la duda.
     */
    private EstadoSii mapearEstado(String codigo) {
        return switch (codigo) {
            case "EPR", "SOK" -> EstadoSii.ACEPTADO;
            case "RCH", "RCT", "RFR", "RSC" -> EstadoSii.RECHAZADO;
            default -> EstadoSii.EN_PROCESO;
        };
    }

    private String extraerEtiqueta(String xml, String etiqueta) {
        String sinEscapar = xml.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        Matcher matcher = Pattern.compile("<" + etiqueta + ">([^<]*)</" + etiqueta + ">").matcher(sinEscapar);
        if (!matcher.find()) {
            throw new IllegalStateException("La respuesta del SII no trae <" + etiqueta + ">");
        }
        return matcher.group(1);
    }

    private String extraerEtiquetaOpcional(String xml, String etiqueta) {
        try {
            return extraerEtiqueta(xml, etiqueta);
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=SiiConsultaClientTest`
Expected: PASS (all five tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cl/timbre/sii/SiiConsultaClient.java src/test/java/cl/timbre/sii/SiiConsultaClientTest.java
git commit -m "feat: consulta de estado de un envio al SII"
```

---

## Task 3: `ConsultaEstadoSiiJob` + `ConsultaEstadoSiiJobScheduler`

**Files:**
- Create: `src/main/java/cl/timbre/sii/ConsultaEstadoSiiJob.java`
- Create: `src/main/java/cl/timbre/sii/ConsultaEstadoSiiJobScheduler.java`
- Modify: `src/test/java/cl/timbre/AbstractIntegrationTest.java`
- Test: `src/test/java/cl/timbre/sii/ConsultaEstadoSiiJobTest.java`

**Interfaces:**
- Consumes: `DocumentRepository.findByEstadoInAndProximaConsultaAtBefore(List<DocumentStatus>, Instant, Pageable)` (already `Pageable`-based from B1), `EmisorRepository.findById(String)`, `SiiAuthClient.obtenerToken(Emisor)` → `Token`, `SiiConsultaClient.consultar(Emisor, Token, String)` → `ResultadoConsulta`, `SiiProperties.consultaMaxIntentos()`/`consultaBackoffMs()`.
- Produces: `ConsultaEstadoSiiJob.consultarEnviados()` — public, no-arg, invocable directly in tests or by `ConsultaEstadoSiiJobScheduler`'s `@Scheduled`.

- [ ] **Step 1: Disable automatic scheduling for the whole integration test suite**

Modify `src/test/java/cl/timbre/AbstractIntegrationTest.java`, add a line to the existing `@DynamicPropertySource` method (after `sii.envio-job-enabled`):

```java
        registry.add("sii.consulta-job-enabled", () -> "false");
```

The full method should read:

```java
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("sii.cert-p12-base64", AbstractIntegrationTest::certificadoDePruebaBase64);
        registry.add("sii.cert-password", () -> "test123");
        registry.add("sii.envio-job-enabled", () -> "false");
        registry.add("sii.consulta-job-enabled", () -> "false");
    }
```

This is infrastructure, not new behavior — run a quick regression check:

Run: `./mvnw test -Dtest=SchemaTest,EnvioSiiJobTest`
Expected: PASS (unchanged)

- [ ] **Step 2: Write the failing integration test**

Create `src/test/java/cl/timbre/sii/ConsultaEstadoSiiJobTest.java`:

```java
package cl.timbre.sii;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.repository.EmisorRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConsultaEstadoSiiJobTest extends AbstractIntegrationTest {

    private static final MockWebServer SII = new MockWebServer();

    static {
        try {
            SII.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void siiProperties(DynamicPropertyRegistry registry) {
        registry.add("sii.base-url", () -> "http://localhost:" + SII.getPort());
        registry.add("sii.consulta-max-intentos", () -> "2");
    }

    @AfterAll
    static void shutdownServer() throws IOException {
        SII.shutdown();
    }

    @Autowired private ConsultaEstadoSiiJob consultaEstadoSiiJob;
    @Autowired private EmisorRepository emisorRepository;
    @Autowired private DocumentRepository documentRepository;

    private Emisor emisor;

    @BeforeEach
    void setUp() {
        emisor = emisorRepository.save(TestFixtures.emisor());
    }

    private Document documentoEnviado(String externalId, String trackId) {
        Document documento = Document.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .externalId(externalId)
                .tipoDte(33)
                .folio(1)
                .rutReceptor("77777777-7")
                .razonSocialReceptor("Constructora Andes SpA")
                .montoNeto(1000000)
                .montoIva(190000)
                .montoTotal(1190000)
                .estado(DocumentStatus.ENVIADO)
                .trackId(trackId)
                .intentosConsulta(0)
                .proximaConsultaAt(Instant.now().minusSeconds(60))
                .build();
        return documentRepository.save(documento);
    }

    private void encolarSemillaYToken() {
        SII.enqueue(new MockResponse()
                .setBody("<SII:RESPUESTA xmlns:SII=\"http://www.sii.cl/XMLSchema\">"
                        + "<SII:RESP_BODY><SEMILLA>111222333</SEMILLA></SII:RESP_BODY></SII:RESPUESTA>"));
        SII.enqueue(new MockResponse()
                .setBody("<SII:RESPUESTA xmlns:SII=\"http://www.sii.cl/XMLSchema\">"
                        + "<SII:RESP_BODY><TOKEN>token-de-prueba</TOKEN></SII:RESP_BODY></SII:RESPUESTA>"));
    }

    private void encolarEstado(String estado, String glosa) {
        SII.enqueue(new MockResponse().setBody(
                "<SII:RESPUESTA xmlns:SII=\"http://www.sii.cl/XMLSchema\">"
                        + "<SII:RESP_BODY><ESTADO>" + estado + "</ESTADO><GLOSA>" + glosa + "</GLOSA></SII:RESP_BODY>"
                        + "</SII:RESPUESTA>"));
    }

    @Test
    void unDocumentoAceptadoQuedaAceptado() {
        Document documento = documentoEnviado("pedido-1", "1000001");
        encolarSemillaYToken();
        encolarEstado("EPR", "Envio Procesado");

        consultaEstadoSiiJob.consultarEnviados();

        Document actualizado = documentRepository.findById(documento.getId()).orElseThrow();
        assertThat(actualizado.getEstado()).isEqualTo(DocumentStatus.ACEPTADO);
        assertThat(actualizado.getProximaConsultaAt()).isNull();
    }

    @Test
    void unDocumentoRechazadoQuedaRechazado() {
        Document documento = documentoEnviado("pedido-2", "1000002");
        encolarSemillaYToken();
        encolarEstado("RCH", "Rechazado por error de firma");

        consultaEstadoSiiJob.consultarEnviados();

        Document actualizado = documentRepository.findById(documento.getId()).orElseThrow();
        assertThat(actualizado.getEstado()).isEqualTo(DocumentStatus.RECHAZADO);
        assertThat(actualizado.getProximaConsultaAt()).isNull();
    }

    @Test
    void unDocumentoTodaviaEnProcesoSeReintenta() {
        Document documento = documentoEnviado("pedido-3", "1000003");
        encolarSemillaYToken();
        encolarEstado("-11", "Envio en proceso");

        consultaEstadoSiiJob.consultarEnviados();

        Document actualizado = documentRepository.findById(documento.getId()).orElseThrow();
        assertThat(actualizado.getEstado()).isEqualTo(DocumentStatus.ENVIADO);
        assertThat(actualizado.getIntentosConsulta()).isEqualTo(1);
        assertThat(actualizado.getProximaConsultaAt()).isAfter(Instant.now());
    }

    @Test
    void unCodigoDesconocidoSeReintentaIgualQueEnProceso() {
        Document documento = documentoEnviado("pedido-4", "1000004");
        encolarSemillaYToken();
        encolarEstado("ZZZ", "Codigo nunca antes visto");

        consultaEstadoSiiJob.consultarEnviados();

        Document actualizado = documentRepository.findById(documento.getId()).orElseThrow();
        assertThat(actualizado.getEstado()).isEqualTo(DocumentStatus.ENVIADO);
        assertThat(actualizado.getIntentosConsulta()).isEqualTo(1);
    }

    @Test
    void alcanzarElMaximoDeIntentosDejaDeReintentar() {
        Document documento = documentoEnviado("pedido-5", "1000005");
        documento.setIntentosConsulta(1);
        documentRepository.save(documento);
        encolarSemillaYToken();
        encolarEstado("-11", "Envio en proceso");

        consultaEstadoSiiJob.consultarEnviados();

        Document actualizado = documentRepository.findById(documento.getId()).orElseThrow();
        assertThat(actualizado.getEstado()).isEqualTo(DocumentStatus.ENVIADO);
        assertThat(actualizado.getIntentosConsulta()).isEqualTo(2);
        assertThat(actualizado.getProximaConsultaAt()).isNull();
    }

    @Test
    void unaFallaDeAutenticacionNoLeCargaElIntentoAlDocumento() {
        Document documento = documentoEnviado("pedido-6", "1000006");
        SII.enqueue(new MockResponse().setResponseCode(500));

        consultaEstadoSiiJob.consultarEnviados();

        Document actualizado = documentRepository.findById(documento.getId()).orElseThrow();
        assertThat(actualizado.getEstado()).isEqualTo(DocumentStatus.ENVIADO);
        assertThat(actualizado.getIntentosConsulta()).isEqualTo(0);
        assertThat(actualizado.getProximaConsultaAt()).isAfter(Instant.now());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -Dtest=ConsultaEstadoSiiJobTest`
Expected: does not compile — `ConsultaEstadoSiiJob` doesn't exist yet.

- [ ] **Step 4: Implement `ConsultaEstadoSiiJob`**

Create `src/main/java/cl/timbre/sii/ConsultaEstadoSiiJob.java`:

```java
package cl.timbre.sii;

import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.repository.EmisorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Consulta al SII el estado de los documentos ENVIADO por su trackId, y los mueve a
 * ACEPTADO/RECHAZADO. Ante cualquier respuesta ambigua, no reconocida, o error de
 * comunicacion, reintenta con backoff propio en vez de asumir un estado -- nunca se
 * marca ACEPTADO ni RECHAZADO ante la duda.
 */
@Component
public class ConsultaEstadoSiiJob {

    private static final Logger log = LoggerFactory.getLogger(ConsultaEstadoSiiJob.class);
    private static final List<DocumentStatus> ESTADOS_CANDIDATOS = List.of(DocumentStatus.ENVIADO);
    private static final int TAMANO_LOTE = 200;

    private final DocumentRepository documentRepository;
    private final EmisorRepository emisorRepository;
    private final SiiAuthClient authClient;
    private final SiiConsultaClient consultaClient;
    private final SiiProperties properties;

    public ConsultaEstadoSiiJob(DocumentRepository documentRepository, EmisorRepository emisorRepository,
                                SiiAuthClient authClient, SiiConsultaClient consultaClient, SiiProperties properties) {
        this.documentRepository = documentRepository;
        this.emisorRepository = emisorRepository;
        this.authClient = authClient;
        this.consultaClient = consultaClient;
        this.properties = properties;
    }

    public void consultarEnviados() {
        List<Document> candidatos = documentRepository.findByEstadoInAndProximaConsultaAtBefore(
                ESTADOS_CANDIDATOS, Instant.now(), PageRequest.of(0, TAMANO_LOTE));

        Map<String, List<Document>> porEmisor = candidatos.stream()
                .collect(Collectors.groupingBy(Document::getEmisorId));

        porEmisor.forEach(this::procesarEmisor);
    }

    private void procesarEmisor(String emisorId, List<Document> documentos) {
        Emisor emisor = emisorRepository.findById(emisorId).orElseThrow();

        SiiAuthClient.Token token;
        try {
            token = authClient.obtenerToken(emisor);
        } catch (Exception e) {
            log.error("No se pudo autenticar con el SII para el emisor {}", emisorId, e);
            reprogramarSinCargarIntento(documentos, e);
            return;
        }

        for (Document documento : documentos) {
            procesarDocumento(emisor, token, documento);
        }
    }

    /**
     * Misma razon que en EnvioSiiJob: una falla de autenticacion es del emisor/SII,
     * no de un documento en particular. No se le carga el intento a nadie.
     */
    private void reprogramarSinCargarIntento(List<Document> documentos, Exception e) {
        Instant proximoIntento = Instant.now().plusMillis(properties.consultaBackoffMs());
        String detalle = "No se pudo autenticar con el SII: " + mensajeTruncado(e);
        for (Document documento : documentos) {
            documento.setSiiEstadoDetalle(detalle);
            documento.setProximaConsultaAt(proximoIntento);
            documentRepository.save(documento);
        }
    }

    private void procesarDocumento(Emisor emisor, SiiAuthClient.Token token, Document documento) {
        try {
            SiiConsultaClient.ResultadoConsulta resultado =
                    consultaClient.consultar(emisor, token, documento.getTrackId());

            switch (resultado.estado()) {
                case ACEPTADO -> finalizar(documento, DocumentStatus.ACEPTADO, resultado.detalle());
                case RECHAZADO -> finalizar(documento, DocumentStatus.RECHAZADO, resultado.detalle());
                case EN_PROCESO -> registrarReintento(documento, resultado.detalle());
            }
        } catch (Exception e) {
            log.error("Fallo la consulta de estado del documento {} folio {}",
                    documento.getId(), documento.getFolio(), e);
            registrarReintento(documento, mensajeTruncado(e));
        }
    }

    private void finalizar(Document documento, DocumentStatus estado, String detalle) {
        log.info("Documento {} folio {} quedo {} en el SII", documento.getId(), documento.getFolio(), estado);
        documento.setEstado(estado);
        documento.setSiiEstadoDetalle(detalle);
        documento.setProximaConsultaAt(null);
        documentRepository.save(documento);
    }

    private void registrarReintento(Document documento, String detalle) {
        int intentos = documento.getIntentosConsulta() + 1;
        documento.setIntentosConsulta(intentos);
        documento.setSiiEstadoDetalle(detalle);
        documento.setProximaConsultaAt(
                intentos < properties.consultaMaxIntentos()
                        ? Instant.now().plusMillis(properties.consultaBackoffMs())
                        : null);
        documentRepository.save(documento);
    }

    private String mensajeTruncado(Exception e) {
        String mensaje = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return mensaje.length() <= 500 ? mensaje : mensaje.substring(0, 500);
    }
}
```

- [ ] **Step 5: Implement `ConsultaEstadoSiiJobScheduler`**

Create `src/main/java/cl/timbre/sii/ConsultaEstadoSiiJobScheduler.java`:

```java
package cl.timbre.sii;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara ConsultaEstadoSiiJob.consultarEnviados() periodicamente. Separado de
 * ConsultaEstadoSiiJob por la misma razon que EnvioSiiJobScheduler: poder
 * desactivar el disparo automatico en tests sin perder la capacidad de invocar el
 * job directamente.
 */
@Component
@ConditionalOnProperty(prefix = "sii", name = "consulta-job-enabled", havingValue = "true", matchIfMissing = true)
public class ConsultaEstadoSiiJobScheduler {

    private final ConsultaEstadoSiiJob job;

    public ConsultaEstadoSiiJobScheduler(ConsultaEstadoSiiJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${sii.consulta-job-fixed-delay-ms}")
    public void ejecutar() {
        job.consultarEnviados();
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=ConsultaEstadoSiiJobTest`
Expected: PASS (all six tests)

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: PASS — every test in the project, confirming the disabled scheduler and everything else still works together.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/cl/timbre/sii/ConsultaEstadoSiiJob.java src/main/java/cl/timbre/sii/ConsultaEstadoSiiJobScheduler.java src/test/java/cl/timbre/AbstractIntegrationTest.java src/test/java/cl/timbre/sii/ConsultaEstadoSiiJobTest.java
git commit -m "feat: job programado que consulta el estado de los DTE enviados"
```

---

## Verificación manual pendiente (no automatizable en este plan)

Se suma a la ya documentada para B1 (`docs/superpowers/plans/2026-08-10-envio-sii.md`). Ningún task de este plan puede confirmar que el formato de `QueryEstUp.jws` ni los códigos de `ESTADO` (`EPR`, `SOK`, `RCH`, `RCT`, `RFR`, `RSC`) coinciden con lo que el SII real devuelve — no hay WSDL en el repo. La misma sesión de verificación manual contra el ambiente de certificación (con un certificado real) que ajusta `SiiAuthClient`/`SiiUploadClient` debe observar respuestas reales de `QueryEstUp.jws` y:

1. Confirmar o corregir los códigos mapeados a `ACEPTADO`/`RECHAZADO` en `SiiConsultaClient.mapearEstado`.
2. Identificar el código real de "aceptado con reparos" y decidir si agregar `ACEPTADO_CON_REPARO` a `EstadoSii` en ese momento.
3. Confirmar el formato exacto del sobre SOAP de `getEstUp` (nombres de parámetros `RutCompania`/`DvCompania`/`Token`/`TrackId` son una suposición, igual que el resto del sobre).

## Self-Review

**Spec coverage:** config de reintentos propia (Task 1), cliente de consulta con mapeo conservador de estados (Task 2), job con agrupación por emisor, reintento sin cargar intento en falla de auth, y nunca marcar terminal ante la duda (Task 3). El alcance explícitamente dejado fuera (`ACEPTADO_CON_REPARO`) queda documentado en Global Constraints y en la verificación manual pendiente. Todo lo del spec está cubierto.

**Placeholder scan:** sin TBD/TODO. Cada paso de código trae la implementación completa.

**Type consistency:** `SiiConsultaClient.EstadoSii`/`ResultadoConsulta` se definen una vez en Task 2 y se consumen sin cambios en Task 3. `SiiProperties`'s 12 campos, en el mismo orden, se usan consistentemente en los cinco archivos de test que la construyen directamente (Tasks 1, 2, 3) y vía Spring Boot binding en el resto.
