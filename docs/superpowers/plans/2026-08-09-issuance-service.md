# IssuanceService Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `POST /api/v1/documents`, the first endpoint that actually issues a DTE — it builds, timbrado (TED), signs (XMLDSig) and wraps (EnvioDTE) a Factura (33) or Nota de Crédito (61), and persists it. It does **not** send it to the SII yet (that's a later phase).

**Architecture:** A new `IssuanceService` orchestrates the pipeline that already exists (`TotalsCalculator` → `FolioAssigner` → `DteXmlBuilder` → `TedBuilder` → `XmlSigner` → `EnvioDteBuilder`) and persists the result as a `Document`. A thin `DocumentController` exposes it over HTTP, reusing the existing `ApiKeyFilter`/`EmisorContext` auth.

**Tech Stack:** Spring Boot 3.4.3, Java 21, Spring Data JPA + Flyway + Postgres (Testcontainers in tests), Lombok, AssertJ + JUnit 5.

## Global Constraints

- Java 21 records for DTOs, matching `IssueLine`/`DteReceptor`/`DteReference`.
- Endpoint path is `/api/v1/documents` (English, plural) — matches the existing `/api/v1/health` convention and the path `ApiKeyFilterTest` already probes.
- `IssuanceService.issue(...)` is **not** `@Transactional` at the method level. Each repository call is its own transaction (same philosophy as `FolioAssigner`, which releases its row lock the instant the folio is incremented rather than holding it through signing). Wrapping the whole method in one transaction would poison it on the first caught exception (Postgres aborts the transaction on the first failed statement), breaking the fallback reads this plan relies on.
- Every `IllegalArgumentException` that crosses a service boundary into the HTTP layer must be translated into an `ApiException` (matches `CafService`/`FolioAssigner`'s existing pattern) — never let a raw exception reach `GlobalExceptionHandler` unhandled.
- Tests follow existing conventions: Spanish method names describing the behavior, AssertJ assertions, integration tests extend `AbstractIntegrationTest` (real Postgres via Testcontainers), pure-logic tests need no Spring context at all.

---

## Task 1: `Document.xmlContent` column

**Files:**
- Create: `src/main/resources/db/migration/V6__document_xml_content.sql`
- Modify: `src/main/java/cl/timbre/domain/Document.java`
- Test: `src/test/java/cl/timbre/repository/DocumentRepositoryTest.java`

**Interfaces:**
- Produces: `Document.getXmlContent()` / `Document.DocumentBuilder.xmlContent(String)` — the signed `EnvioDTE` XML (ISO-8859-1 bytes decoded as a `String`), nullable. Later tasks in this plan set it.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/cl/timbre/repository/DocumentRepositoryTest.java`:

```java
package cl.timbre.repository;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRepositoryTest extends AbstractIntegrationTest {

    @Autowired private DocumentRepository documentRepository;
    @Autowired private EmisorRepository emisorRepository;

    private Emisor emisor;

    @BeforeEach
    void setUp() {
        emisor = emisorRepository.save(TestFixtures.emisor());
    }

    @Test
    void persisteYRecuperaElXmlDelSobreFirmado() {
        Document document = Document.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .externalId("pedido-1")
                .tipoDte(33)
                .folio(1)
                .rutReceptor("77777777-7")
                .razonSocialReceptor("Constructora Andes SpA")
                .montoNeto(1000000)
                .montoIva(190000)
                .montoTotal(1190000)
                .estado(DocumentStatus.PENDIENTE_ENVIO)
                .xmlContent("<EnvioDTE>contenido de prueba</EnvioDTE>")
                .build();

        documentRepository.save(document);

        Document recuperado = documentRepository
                .findByEmisorIdAndExternalId(emisor.getId(), "pedido-1")
                .orElseThrow();
        assertThat(recuperado.getXmlContent()).isEqualTo("<EnvioDTE>contenido de prueba</EnvioDTE>");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=DocumentRepositoryTest`
Expected: does not compile — `Document.DocumentBuilder` has no method `xmlContent(String)`.

- [ ] **Step 3: Add the migration**

Create `src/main/resources/db/migration/V6__document_xml_content.sql`:

```sql
ALTER TABLE document ADD COLUMN xml_content TEXT;
```

- [ ] **Step 4: Add the field to the entity**

Modify `src/main/java/cl/timbre/domain/Document.java` — add after the `xmlKey` field (line 50-51):

```java
    @Column(name = "xml_key")
    private String xmlKey;

    @Column(name = "xml_content")
    private String xmlContent;

```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=DocumentRepositoryTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V6__document_xml_content.sql src/main/java/cl/timbre/domain/Document.java src/test/java/cl/timbre/repository/DocumentRepositoryTest.java
git commit -m "feat: columna xml_content para guardar el sobre firmado"
```

---

## Task 2: `CafParser.privateKey` desde un PEM guardado

**Files:**
- Modify: `src/main/java/cl/timbre/caf/CafParser.java:41-49`
- Test: `src/test/java/cl/timbre/caf/CafParserTest.java`

**Interfaces:**
- Produces: `CafParser.privateKey(String privateKeyPem)` — nueva sobrecarga. `CafParser.privateKey(Caf caf)` sigue existiendo, delega a la nueva. `IssuanceService` (Task 3) la necesita porque `FolioRange` solo guarda el PEM (`getPrivateKeyPem()`), no un `Caf` completo.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/cl/timbre/caf/CafParserTest.java` (after `cargaLaLlavePrivadaEnFormatoPkcs1`, around line 65):

```java
    @Test
    void laLlaveSePuedeCargarDirectoDesdeElPemGuardado() throws Exception {
        Caf caf = CafParser.parse(ejemplo());

        PrivateKey desdePem = CafParser.privateKey(caf.privateKeyPem());

        assertThat(desdePem.getEncoded()).isEqualTo(CafParser.privateKey(caf).getEncoded());
    }
```

Add the import near the top of the file:

```java
import java.security.PrivateKey;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CafParserTest`
Expected: does not compile — `CafParser.privateKey(String)` doesn't exist yet.

- [ ] **Step 3: Add the overload**

Modify `src/main/java/cl/timbre/caf/CafParser.java`, replace the existing `privateKey` method (lines 41-49):

```java
    public static PrivateKey privateKey(Caf caf) {
        return privateKey(caf.privateKeyPem());
    }

    public static PrivateKey privateKey(String privateKeyPem) {
        try {
            byte[] pkcs1 = Base64.getMimeDecoder().decode(stripPem(privateKeyPem));
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return factory.generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1InPkcs8(pkcs1)));
        } catch (Exception e) {
            throw new IllegalArgumentException("La llave privada del CAF no se pudo leer", e);
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=CafParserTest`
Expected: PASS (all `CafParserTest` tests, including the new one)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cl/timbre/caf/CafParser.java src/test/java/cl/timbre/caf/CafParserTest.java
git commit -m "refactor: leer la llave del CAF directo desde el PEM guardado"
```

---

## Task 3: `IssuanceService` — camino feliz

**Files:**
- Create: `src/main/java/cl/timbre/dto/ReceptorRequest.java`
- Create: `src/main/java/cl/timbre/dto/ReferenciaRequest.java`
- Create: `src/main/java/cl/timbre/dto/IssueDocumentRequest.java`
- Create: `src/main/java/cl/timbre/issue/IssuanceService.java`
- Modify: `src/test/java/cl/timbre/AbstractIntegrationTest.java`
- Test: `src/test/java/cl/timbre/issue/IssuanceServiceTest.java`

**Interfaces:**
- Consumes: `TotalsCalculator.compute(List<IssueLine>)` → `DteTotals`; `FolioAssigner.assign(String emisorId, int tipoDte)` → `FolioAssigner.AssignedFolio(int folio, FolioRange range)`; `FolioRange.getCafXml()`/`getPrivateKeyPem()`; `CafParser.privateKey(String)`; `DteXmlBuilder.build(DteDocument)`, `DteXmlBuilder.documentId(int,int)`; `TedBuilder.build(Document owner, DteDocument dte, String cafElementXml, PrivateKey cafKey, LocalDateTime timestamp)` → `Element`; `XmlSigner.sign(Document doc, Element parent, String referenceId, SigningMaterial material)`; `EnvioDteBuilder.build(Emisor, List<Document>, SigningMaterial, LocalDateTime)` → `byte[]`; `CertificateProvider.forEmisor(Emisor)` → `SigningMaterial`; `DocumentRepository.findByEmisorIdAndExternalId(String, String)`, `.save(Document)`.
- Produces: `IssuanceService.issue(Emisor emisor, IssueDocumentRequest request)` → `cl.timbre.domain.Document`. This is what `DocumentController` (Task 5) calls. `IssueDocumentRequest(String externalId, int tipoDte, LocalDate fechaEmision, ReceptorRequest receptor, List<IssueLine> lineas, List<ReferenciaRequest> referencias)`. `ReceptorRequest(String rut, String razonSocial, String giro, String direccion, String comuna)`. `ReferenciaRequest(int tipoDocRef, int folioRef, LocalDate fechaRef, int codigoRef, String razonRef)`.

This task covers only the happy path: idempotent replay, computing totals, assigning a folio, and building+signing+persisting a `PENDIENTE_ENVIO` document. Unsupported `tipoDte`, missing NC references, mid-build failures, and the duplicate-`externalId` race are Task 4.

- [ ] **Step 1: Wire a test certificate into `AbstractIntegrationTest`**

`IssuanceService` needs `CertificateProvider.forEmisor(...)` to work, which needs `sii.cert-p12-base64`/`sii.cert-password` — currently unset for integration tests. Modify `src/test/java/cl/timbre/AbstractIntegrationTest.java`:

```java
package cl.timbre;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    // static: un solo contenedor reutilizado por toda la suite, no uno por clase.
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("sii.cert-p12-base64", AbstractIntegrationTest::certificadoDePruebaBase64);
        registry.add("sii.cert-password", () -> "test123");
    }

    private static String certificadoDePruebaBase64() {
        try {
            return Base64.getEncoder().encodeToString(
                    Files.readAllBytes(Path.of("src/test/resources/test-cert.p12")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void limpiarBaseDeDatos() {
        // TRUNCATE ... CASCADE limpia emisor y todo lo que depende de el (directa o
        // transitivamente via FK) en una sola sentencia, sin que cada subclase tenga
        // que enumerar las tablas hijas en el orden correcto. Nuevas tablas con FK a
        // emisor quedan cubiertas automaticamente, sin tocar este metodo.
        jdbcTemplate.execute("TRUNCATE TABLE emisor RESTART IDENTITY CASCADE");
    }
}
```

This is infrastructure, not new behavior — run the existing suite to confirm nothing broke:

Run: `./mvnw test -Dtest=SchemaTest,FolioAssignerTest,ApiKeyFilterTest`
Expected: PASS (unchanged — these tests don't touch `CertificateProvider`)

- [ ] **Step 2: Create the request DTOs**

Create `src/main/java/cl/timbre/dto/ReceptorRequest.java`:

```java
package cl.timbre.dto;

import jakarta.validation.constraints.NotBlank;

public record ReceptorRequest(
        @NotBlank String rut,
        @NotBlank String razonSocial,
        @NotBlank String giro,
        @NotBlank String direccion,
        @NotBlank String comuna
) {}
```

Create `src/main/java/cl/timbre/dto/ReferenciaRequest.java`:

```java
package cl.timbre.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record ReferenciaRequest(
        int tipoDocRef,
        int folioRef,
        @NotNull LocalDate fechaRef,
        int codigoRef,
        @NotBlank String razonRef
) {}
```

Create `src/main/java/cl/timbre/dto/IssueDocumentRequest.java`:

```java
package cl.timbre.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.LocalDate;
import java.util.List;

public record IssueDocumentRequest(
        @NotBlank String externalId,
        int tipoDte,
        @NotNull @PastOrPresent LocalDate fechaEmision,
        @NotNull @Valid ReceptorRequest receptor,
        @NotEmpty @Valid List<IssueLine> lineas,
        @Valid List<ReferenciaRequest> referencias
) {
    public IssueDocumentRequest {
        if (referencias == null) {
            referencias = List.of();
        }
    }
}
```

No dedicated test for these three records — they're plain data holders whose validation annotations get exercised by `IssuanceServiceTest`/`DocumentControllerTest` (Tasks 3-5), same as `IssueLine` today.

- [ ] **Step 3: Write the failing test for the happy path**

Create `src/test/java/cl/timbre/issue/IssuanceServiceTest.java`:

```java
package cl.timbre.issue;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.caf.CafService;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.dto.IssueDocumentRequest;
import cl.timbre.dto.IssueLine;
import cl.timbre.dto.LineType;
import cl.timbre.dto.ReceptorRequest;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.xml.XsdValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IssuanceServiceTest extends AbstractIntegrationTest {

    @Autowired private IssuanceService issuanceService;
    @Autowired private CafService cafService;
    @Autowired private EmisorRepository emisorRepository;

    private Emisor emisor;

    @BeforeEach
    void setUp() throws Exception {
        emisor = emisorRepository.save(TestFixtures.emisor());
        cafService.register(emisor, Files.readAllBytes(
                Path.of("src/test/resources/sii/caf-33-ejemplo.xml")));
    }

    private IssueDocumentRequest requestFactura(String externalId) {
        return new IssueDocumentRequest(externalId, 33, LocalDate.of(2026, 8, 9),
                new ReceptorRequest("77777777-7", "Constructora Andes SpA", "Construccion",
                        "Av. Apoquindo 4500", "Las Condes"),
                List.of(new IssueLine("Generador", 1, 1190000, LineType.AFECTO)),
                List.of());
    }

    @Test
    void emiteUnaFacturaYQuedaPendienteDeEnvio() {
        Document document = issuanceService.issue(emisor, requestFactura("pedido-1"));

        assertThat(document.getFolio()).isEqualTo(1);
        assertThat(document.getEstado()).isEqualTo(DocumentStatus.PENDIENTE_ENVIO);
        assertThat(document.getMontoNeto()).isEqualTo(1000000);
        assertThat(document.getMontoIva()).isEqualTo(190000);
        assertThat(document.getMontoTotal()).isEqualTo(1190000);
        assertThat(document.getXmlContent()).isNotBlank();
    }

    @Test
    void elSegundoDocumentoUsaElSiguienteFolio() {
        issuanceService.issue(emisor, requestFactura("pedido-1"));
        Document segundo = issuanceService.issue(emisor, requestFactura("pedido-2"));

        assertThat(segundo.getFolio()).isEqualTo(2);
    }

    @Test
    void repetirElMismoExternalIdDevuelveElMismoDocumentoSinConsumirFolioNuevo() {
        Document primero = issuanceService.issue(emisor, requestFactura("pedido-repetido"));
        Document repetido = issuanceService.issue(emisor, requestFactura("pedido-repetido"));

        assertThat(repetido.getId()).isEqualTo(primero.getId());
        assertThat(repetido.getFolio()).isEqualTo(primero.getFolio());
    }

    @Test
    void elXmlGeneradoValidaContraElXsdDelSii() throws Exception {
        Document document = issuanceService.issue(emisor, requestFactura("pedido-1"));

        byte[] xml = document.getXmlContent().getBytes(StandardCharsets.ISO_8859_1);
        assertThat(XsdValidator.errores(xml, "src/test/resources/sii/xsd/EnvioDTE_v10.xsd")).isEmpty();
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./mvnw test -Dtest=IssuanceServiceTest`
Expected: does not compile — `IssuanceService` doesn't exist yet.

- [ ] **Step 5: Implement the happy path**

Create `src/main/java/cl/timbre/issue/IssuanceService.java`:

```java
package cl.timbre.issue;

import cl.timbre.caf.CafParser;
import cl.timbre.caf.FolioAssigner;
import cl.timbre.calc.DteTotals;
import cl.timbre.calc.TotalsCalculator;
import cl.timbre.cert.CertificateProvider;
import cl.timbre.cert.SigningMaterial;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.dto.IssueDocumentRequest;
import cl.timbre.dto.ReferenciaRequest;
import cl.timbre.exception.ApiException;
import cl.timbre.model.DteDocument;
import cl.timbre.model.DteReceptor;
import cl.timbre.model.DteReference;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.xml.DteXmlBuilder;
import cl.timbre.xml.EnvioDteBuilder;
import cl.timbre.xml.TedBuilder;
import cl.timbre.xml.XmlSigner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class IssuanceService {

    private final DocumentRepository documentRepository;
    private final FolioAssigner folioAssigner;
    private final CertificateProvider certificateProvider;

    public IssuanceService(DocumentRepository documentRepository, FolioAssigner folioAssigner,
                           CertificateProvider certificateProvider) {
        this.documentRepository = documentRepository;
        this.folioAssigner = folioAssigner;
        this.certificateProvider = certificateProvider;
    }

    public Document issue(Emisor emisor, IssueDocumentRequest request) {
        var existente = documentRepository.findByEmisorIdAndExternalId(emisor.getId(), request.externalId());
        if (existente.isPresent()) {
            return existente.get();
        }

        DteTotals totales;
        try {
            totales = TotalsCalculator.compute(request.lineas());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "lineas_invalidas", e.getMessage());
        }

        FolioAssigner.AssignedFolio asignado = folioAssigner.assign(emisor.getId(), request.tipoDte());
        LocalDateTime timestamp = LocalDateTime.now();

        byte[] sobre = construirSobreFirmado(emisor, request, totales, asignado.folio(),
                asignado.range().getCafXml(), asignado.range().getPrivateKeyPem(), timestamp);

        Document document = Document.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .externalId(request.externalId())
                .tipoDte(request.tipoDte())
                .folio(asignado.folio())
                .rutReceptor(request.receptor().rut())
                .razonSocialReceptor(request.receptor().razonSocial())
                .montoNeto(totales.montoNeto())
                .montoIva(totales.iva())
                .montoTotal(totales.montoTotal())
                .estado(DocumentStatus.PENDIENTE_ENVIO)
                .xmlContent(new String(sobre, StandardCharsets.ISO_8859_1))
                .build();

        return documentRepository.save(document);
    }

    private byte[] construirSobreFirmado(Emisor emisor, IssueDocumentRequest request, DteTotals totales,
                                         int folio, String cafElementXml, String cafPrivateKeyPem,
                                         LocalDateTime timestamp) {
        List<DteReference> referencias = new ArrayList<>();
        int numero = 1;
        for (ReferenciaRequest r : request.referencias()) {
            referencias.add(new DteReference(numero++, r.tipoDocRef(), r.folioRef(),
                    r.fechaRef(), r.codigoRef(), r.razonRef()));
        }

        DteDocument dte = new DteDocument(emisor,
                new DteReceptor(request.receptor().rut(), request.receptor().razonSocial(),
                        request.receptor().giro(), request.receptor().direccion(), request.receptor().comuna()),
                request.tipoDte(), folio, request.fechaEmision(), totales, referencias);

        org.w3c.dom.Document doc = DteXmlBuilder.build(dte);
        Element documento = (Element) doc.getElementsByTagNameNS(DteXmlBuilder.NS, "Documento").item(0);

        PrivateKey cafKey = CafParser.privateKey(cafPrivateKeyPem);
        Element ted = TedBuilder.build(doc, dte, cafElementXml, cafKey, timestamp);
        documento.appendChild(ted);

        Element tmstFirma = doc.createElementNS(DteXmlBuilder.NS, "TmstFirma");
        tmstFirma.setTextContent(timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        documento.appendChild(tmstFirma);

        SigningMaterial material = certificateProvider.forEmisor(emisor);
        XmlSigner.sign(doc, doc.getDocumentElement(),
                DteXmlBuilder.documentId(request.tipoDte(), folio), material);

        return EnvioDteBuilder.build(emisor, List.of(doc), material, timestamp);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=IssuanceServiceTest`
Expected: PASS (all four tests)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/cl/timbre/dto/ReceptorRequest.java src/main/java/cl/timbre/dto/ReferenciaRequest.java src/main/java/cl/timbre/dto/IssueDocumentRequest.java src/main/java/cl/timbre/issue/IssuanceService.java src/test/java/cl/timbre/AbstractIntegrationTest.java src/test/java/cl/timbre/issue/IssuanceServiceTest.java
git commit -m "feat: IssuanceService arma, timbra y firma un DTE (camino feliz)"
```

---

## Task 4: `IssuanceService` — validaciones, error y concurrencia

**Files:**
- Modify: `src/main/java/cl/timbre/issue/IssuanceService.java`
- Test: `src/test/java/cl/timbre/issue/IssuanceServiceTest.java`

**Interfaces:**
- Consumes: everything from Task 3, plus `org.springframework.dao.DataIntegrityViolationException` (thrown by `documentRepository.save(...)` when the `document_external_unico` unique constraint is hit).
- Produces: same `IssuanceService.issue(...)` signature — no change to callers. `IssuanceService`'s public surface is unchanged; only internal behavior gains validation, error persistence, and race-safety.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/cl/timbre/issue/IssuanceServiceTest.java` (add these imports: `cl.timbre.exception.ApiException`, `cl.timbre.dto.ReferenciaRequest`, `org.springframework.beans.factory.annotation.Autowired` for `DocumentRepository`, `cl.timbre.cert.CertificateProvider`, `cl.timbre.repository.DocumentRepository`, `java.nio.file.Files`/`Path` already present, `java.util.concurrent.*`, and `static org.assertj.core.api.Assertions.assertThatThrownBy`):

```java
    @Autowired private DocumentRepository documentRepository;
    @Autowired private CertificateProvider certificateProvider;

    @Test
    void unTipoDteNoSoportadoFalla() {
        IssueDocumentRequest request = new IssueDocumentRequest("pedido-1", 39, LocalDate.of(2026, 8, 9),
                new ReceptorRequest("77777777-7", "Constructora Andes SpA", "Construccion",
                        "Av. Apoquindo 4500", "Las Condes"),
                List.of(new IssueLine("Generador", 1, 1190000, LineType.AFECTO)),
                List.of());

        assertThatThrownBy(() -> issuanceService.issue(emisor, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("39");
    }

    @Test
    void unaNotaDeCreditoSinReferenciasFalla() {
        IssueDocumentRequest request = new IssueDocumentRequest("pedido-1", 61, LocalDate.of(2026, 8, 9),
                new ReceptorRequest("77777777-7", "Constructora Andes SpA", "Construccion",
                        "Av. Apoquindo 4500", "Las Condes"),
                List.of(new IssueLine("Generador", 1, 1190000, LineType.AFECTO)),
                List.of());

        assertThatThrownBy(() -> issuanceService.issue(emisor, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("referencia");
    }

    @Test
    void siLaFirmaFallaElDocumentoQuedaEnErrorEnvioConElFolioConsumido() throws Exception {
        String base64 = Base64.getEncoder().encodeToString(
                Files.readAllBytes(Path.of("src/test/resources/test-cert.p12")));
        IssuanceService servicioConCertificadoInvalido = new IssuanceService(
                documentRepository, folioAssigner, new CertificateProvider(base64, "clave-mala"));

        assertThatThrownBy(() -> servicioConCertificadoInvalido.issue(emisor, requestFactura("pedido-fallido")))
                .isInstanceOf(ApiException.class);

        Document guardado = documentRepository.findByEmisorIdAndExternalId(emisor.getId(), "pedido-fallido")
                .orElseThrow();
        assertThat(guardado.getEstado()).isEqualTo(DocumentStatus.ERROR_ENVIO);
        assertThat(guardado.getFolio()).isEqualTo(1);
        assertThat(guardado.getXmlContent()).isNull();
    }

    @Test
    void dosRequestsConElMismoExternalIdEnParaleloNoDuplicanElDocumento() throws Exception {
        CyclicBarrier arranqueSincronizado = new CyclicBarrier(2);
        Callable<Document> tarea = () -> {
            arranqueSincronizado.await();
            return issuanceService.issue(emisor, requestFactura("pedido-carrera"));
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Document> resultados = pool.invokeAll(List.of(tarea, tarea)).stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
        pool.shutdown();

        assertThat(resultados.get(0).getId()).isEqualTo(resultados.get(1).getId());
        assertThat(documentRepository.findByEmisorIdAndExternalId(emisor.getId(), "pedido-carrera")).isPresent();
    }
```

Also add `@Autowired private FolioAssigner folioAssigner;` to the test class (needed to construct `servicioConCertificadoInvalido`), and this import block:

```java
import cl.timbre.caf.FolioAssigner;
import cl.timbre.cert.CertificateProvider;
import cl.timbre.dto.ReceptorRequest;
import cl.timbre.exception.ApiException;
import cl.timbre.repository.DocumentRepository;

import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=IssuanceServiceTest`
Expected: `unTipoDteNoSoportadoFalla` fails because `folioAssigner.assign(emisorId, 39)` throws `ApiException` with `"sin_folios"` (409, wrong reason) or a stack trace unrelated to `"39"`; `unaNotaDeCreditoSinReferenciasFalla` fails because it currently emits a real document instead of throwing; `siLaFirmaFallaElDocumentoQuedaEnErrorEnvioConElFolioConsumido` fails because today an emission failure throws but never persists a `Document`; `dosRequestsConElMismoExternalIdEnParaleloNoDuplicanElDocumento` fails intermittently with a raw `DataIntegrityViolationException` instead of resolving to one document.

- [ ] **Step 3: Implement validation, error handling, and the race-safe save**

Replace the body of `src/main/java/cl/timbre/issue/IssuanceService.java` with:

```java
package cl.timbre.issue;

import cl.timbre.caf.CafParser;
import cl.timbre.caf.FolioAssigner;
import cl.timbre.calc.DteTotals;
import cl.timbre.calc.TotalsCalculator;
import cl.timbre.cert.CertificateProvider;
import cl.timbre.cert.SigningMaterial;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.dto.IssueDocumentRequest;
import cl.timbre.dto.ReferenciaRequest;
import cl.timbre.exception.ApiException;
import cl.timbre.model.DteDocument;
import cl.timbre.model.DteReceptor;
import cl.timbre.model.DteReference;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.xml.DteXmlBuilder;
import cl.timbre.xml.EnvioDteBuilder;
import cl.timbre.xml.TedBuilder;
import cl.timbre.xml.XmlSigner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class IssuanceService {

    private static final Set<Integer> TIPOS_SOPORTADOS = Set.of(33, 61);

    private final DocumentRepository documentRepository;
    private final FolioAssigner folioAssigner;
    private final CertificateProvider certificateProvider;

    public IssuanceService(DocumentRepository documentRepository, FolioAssigner folioAssigner,
                           CertificateProvider certificateProvider) {
        this.documentRepository = documentRepository;
        this.folioAssigner = folioAssigner;
        this.certificateProvider = certificateProvider;
    }

    public Document issue(Emisor emisor, IssueDocumentRequest request) {
        var existente = documentRepository.findByEmisorIdAndExternalId(emisor.getId(), request.externalId());
        if (existente.isPresent()) {
            return existente.get();
        }
        if (!TIPOS_SOPORTADOS.contains(request.tipoDte())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "tipo_dte_no_soportado",
                    "Tipo de documento no soportado: " + request.tipoDte());
        }
        if (request.tipoDte() == 61 && request.referencias().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "referencia_requerida",
                    "Una nota de credito debe referenciar el documento que corrige");
        }

        DteTotals totales;
        try {
            totales = TotalsCalculator.compute(request.lineas());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "lineas_invalidas", e.getMessage());
        }

        FolioAssigner.AssignedFolio asignado = folioAssigner.assign(emisor.getId(), request.tipoDte());
        LocalDateTime timestamp = LocalDateTime.now();

        Document.DocumentBuilder documento = Document.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .externalId(request.externalId())
                .tipoDte(request.tipoDte())
                .folio(asignado.folio())
                .rutReceptor(request.receptor().rut())
                .razonSocialReceptor(request.receptor().razonSocial())
                .montoNeto(totales.montoNeto())
                .montoIva(totales.iva())
                .montoTotal(totales.montoTotal());

        try {
            byte[] sobre = construirSobreFirmado(emisor, request, totales, asignado.folio(),
                    asignado.range().getCafXml(), asignado.range().getPrivateKeyPem(), timestamp);
            return guardar(documento
                    .estado(DocumentStatus.PENDIENTE_ENVIO)
                    .xmlContent(new String(sobre, StandardCharsets.ISO_8859_1))
                    .build());
        } catch (Exception e) {
            guardar(documento
                    .estado(DocumentStatus.ERROR_ENVIO)
                    .siiEstadoDetalle(mensajeTruncado(e))
                    .build());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error_emision",
                    "No se pudo emitir el documento");
        }
    }

    private byte[] construirSobreFirmado(Emisor emisor, IssueDocumentRequest request, DteTotals totales,
                                         int folio, String cafElementXml, String cafPrivateKeyPem,
                                         LocalDateTime timestamp) {
        List<DteReference> referencias = new ArrayList<>();
        int numero = 1;
        for (ReferenciaRequest r : request.referencias()) {
            referencias.add(new DteReference(numero++, r.tipoDocRef(), r.folioRef(),
                    r.fechaRef(), r.codigoRef(), r.razonRef()));
        }

        DteDocument dte = new DteDocument(emisor,
                new DteReceptor(request.receptor().rut(), request.receptor().razonSocial(),
                        request.receptor().giro(), request.receptor().direccion(), request.receptor().comuna()),
                request.tipoDte(), folio, request.fechaEmision(), totales, referencias);

        org.w3c.dom.Document doc = DteXmlBuilder.build(dte);
        Element documento = (Element) doc.getElementsByTagNameNS(DteXmlBuilder.NS, "Documento").item(0);

        PrivateKey cafKey = CafParser.privateKey(cafPrivateKeyPem);
        Element ted = TedBuilder.build(doc, dte, cafElementXml, cafKey, timestamp);
        documento.appendChild(ted);

        Element tmstFirma = doc.createElementNS(DteXmlBuilder.NS, "TmstFirma");
        tmstFirma.setTextContent(timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        documento.appendChild(tmstFirma);

        SigningMaterial material = certificateProvider.forEmisor(emisor);
        XmlSigner.sign(doc, doc.getDocumentElement(),
                DteXmlBuilder.documentId(request.tipoDte(), folio), material);

        return EnvioDteBuilder.build(emisor, List.of(doc), material, timestamp);
    }

    /**
     * Si dos requests con el mismo externalId llegan a la vez, ambas pasan el chequeo de
     * idempotencia de mas arriba y cada una alcanza a consumir su propio folio antes de
     * intentar guardar. La segunda en llegar aca choca con el UNIQUE(emisor_id, external_id)
     * y se resuelve devolviendo el documento de la que gano la carrera; el folio de la que
     * perdio queda consumido sin documento asociado, igual que cualquier otro folio quemado
     * por un error de emision — es una realidad operacional normal en DTE, no un bug.
     */
    private Document guardar(Document document) {
        try {
            return documentRepository.save(document);
        } catch (DataIntegrityViolationException e) {
            return documentRepository
                    .findByEmisorIdAndExternalId(document.getEmisorId(), document.getExternalId())
                    .orElseThrow(() -> e);
        }
    }

    private String mensajeTruncado(Exception e) {
        String mensaje = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return mensaje.length() <= 500 ? mensaje : mensaje.substring(0, 500);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=IssuanceServiceTest`
Expected: PASS (all eight tests). Run it 2-3 times in a row to build confidence the concurrency test isn't flaky:

Run: `./mvnw test -Dtest=IssuanceServiceTest#dosRequestsConElMismoExternalIdEnParaleloNoDuplicanElDocumento`
Expected: PASS every time

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cl/timbre/issue/IssuanceService.java src/test/java/cl/timbre/issue/IssuanceServiceTest.java
git commit -m "feat: valida tipo de documento y referencias, y protege folios ante errores y carreras"
```

---

## Task 5: `POST /api/v1/documents`

**Files:**
- Create: `src/main/java/cl/timbre/dto/DocumentResponse.java`
- Create: `src/main/java/cl/timbre/issue/DocumentController.java`
- Modify: `src/test/java/cl/timbre/auth/ApiKeyFilterTest.java:49-53`
- Test: `src/test/java/cl/timbre/issue/DocumentControllerTest.java`

**Interfaces:**
- Consumes: `IssuanceService.issue(Emisor, IssueDocumentRequest)`, `EmisorContext.current()` (already populated by `ApiKeyFilter`).
- Produces: `DocumentResponse.from(Document)` — the JSON shape returned to callers.

- [ ] **Step 1: Fix the now-stale `ApiKeyFilterTest` expectation**

`GET /api/v1/documents` currently returns 404 (no route exists at all). Once this task adds a `POST` mapping on that exact path, Spring matches the path but not the method, so it returns 405 instead. Modify `src/test/java/cl/timbre/auth/ApiKeyFilterTest.java`, replace lines 49-53:

```java
    @Test
    void conKeyValidaNoDevuelve401() throws Exception {
        // GET no tiene handler en /api/v1/documents (solo POST, ver DocumentController),
        // asi que el filtro deja pasar la auth y Spring responde 405, no 401.
        mockMvc.perform(get("/api/v1/documents").header("Authorization", "Bearer " + plainKey))
                .andExpect(status().isMethodNotAllowed());
    }
```

Run: `./mvnw test -Dtest=ApiKeyFilterTest`
Expected: this specific test now FAILS (still expects 404 today, route doesn't exist until Step 3) — confirms the test file changed correctly and will flip green once the controller exists.

- [ ] **Step 2: Create the response DTO**

Create `src/main/java/cl/timbre/dto/DocumentResponse.java`:

```java
package cl.timbre.dto;

import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record DocumentResponse(
        String id,
        String externalId,
        int tipoDte,
        int folio,
        DocumentStatus estado,
        int montoNeto,
        int montoIva,
        int montoTotal,
        String xmlBase64
) {
    public static DocumentResponse from(Document document) {
        String xmlBase64 = document.getXmlContent() == null
                ? null
                : Base64.getEncoder().encodeToString(
                        document.getXmlContent().getBytes(StandardCharsets.ISO_8859_1));

        return new DocumentResponse(document.getId(), document.getExternalId(), document.getTipoDte(),
                document.getFolio(), document.getEstado(), document.getMontoNeto(), document.getMontoIva(),
                document.getMontoTotal(), xmlBase64);
    }
}
```

- [ ] **Step 3: Write the failing controller test**

Create `src/test/java/cl/timbre/issue/DocumentControllerTest.java`:

```java
package cl.timbre.issue;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.auth.ApiKeyService;
import cl.timbre.caf.CafService;
import cl.timbre.domain.Emisor;
import cl.timbre.repository.EmisorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class DocumentControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApiKeyService apiKeyService;
    @Autowired private EmisorRepository emisorRepository;
    @Autowired private CafService cafService;

    private String apiKey;

    private static final String CUERPO_VALIDO = """
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
              ]
            }
            """;

    @BeforeEach
    void setUp() throws Exception {
        Emisor emisor = emisorRepository.save(TestFixtures.emisor());
        cafService.register(emisor, Files.readAllBytes(
                Path.of("src/test/resources/sii/caf-33-ejemplo.xml")));
        apiKey = apiKeyService.generate(emisor.getId(), "test").plainKey();
    }

    @Test
    void emitirUnDocumentoValidoDevuelve200ConElXmlBase64() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType("application/json")
                        .content(CUERPO_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folio").value(1))
                .andExpect(jsonPath("$.estado").value("PENDIENTE_ENVIO"))
                .andExpect(jsonPath("$.xmlBase64").isNotEmpty());
    }

    @Test
    void sinReceptorDevuelve400() throws Exception {
        String cuerpoSinReceptor = """
                {
                  "externalId": "pedido-8843",
                  "tipoDte": 33,
                  "fechaEmision": "2026-08-09",
                  "lineas": [
                    { "descripcion": "Generador", "cantidad": 1, "precioUnitarioBruto": 1190000, "tipo": "AFECTO" }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/documents")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType("application/json")
                        .content(cuerpoSinReceptor))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sinApiKeyDevuelve401() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .contentType("application/json")
                        .content(CUERPO_VALIDO))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./mvnw test -Dtest=DocumentControllerTest`
Expected: FAIL — no route exists yet, all three requests hit the default 404/401-before-404 handling (the first two won't get a matching controller).

- [ ] **Step 5: Implement the controller**

Create `src/main/java/cl/timbre/issue/DocumentController.java`:

```java
package cl.timbre.issue;

import cl.timbre.auth.EmisorContext;
import cl.timbre.dto.DocumentResponse;
import cl.timbre.dto.IssueDocumentRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final IssuanceService issuanceService;

    public DocumentController(IssuanceService issuanceService) {
        this.issuanceService = issuanceService;
    }

    @PostMapping
    public DocumentResponse emitir(@Valid @RequestBody IssueDocumentRequest request) {
        return DocumentResponse.from(issuanceService.issue(EmisorContext.current(), request));
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw test -Dtest=DocumentControllerTest,ApiKeyFilterTest`
Expected: PASS (all `DocumentControllerTest` tests, and `ApiKeyFilterTest` including the updated `conKeyValidaNoDevuelve401`)

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: PASS — every test in the project, confirming nothing else regressed.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/cl/timbre/dto/DocumentResponse.java src/main/java/cl/timbre/issue/DocumentController.java src/test/java/cl/timbre/issue/DocumentControllerTest.java src/test/java/cl/timbre/auth/ApiKeyFilterTest.java
git commit -m "feat: POST /api/v1/documents emite un DTE de punta a punta"
```

---

## Self-Review

**Spec coverage:** endpoint contract (Task 5), idempotencia (Task 3 + Task 4 race), cálculo de totales (Task 3, reutiliza `TotalsCalculator`), asignación de folio (Task 3, reutiliza `FolioAssigner`), construcción+timbre+firma+sobre (Task 3, reutiliza el pipeline existente), persistencia con `xml_content` (Task 1 + Task 3), manejo de error post-folio con `ERROR_ENVIO` (Task 4), carrera de `externalId` duplicado (Task 4), tipos soportados 33/61 y validación de NC (Task 4), refactor de `CafParser.privateKey` (Task 2). Todo lo del spec está cubierto.

**Placeholder scan:** sin TBD/TODO. Cada paso de código trae la implementación completa, no descripciones.

**Type consistency:** `IssuanceService.issue(Emisor, IssueDocumentRequest)` devuelve `cl.timbre.domain.Document` en las tres tareas que lo tocan (3, 4, 5) — firma estable. `IssueDocumentRequest`/`ReceptorRequest`/`ReferenciaRequest` se definen una vez en Task 3 y no cambian. `DocumentResponse.from(Document)` en Task 5 coincide con el tipo que devuelve `IssuanceService.issue`.
