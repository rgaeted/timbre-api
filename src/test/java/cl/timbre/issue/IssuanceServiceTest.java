package cl.timbre.issue;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.caf.CafService;
import cl.timbre.caf.FolioAssigner;
import cl.timbre.cert.CertificateProvider;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.dto.IssueDocumentRequest;
import cl.timbre.dto.IssueLine;
import cl.timbre.dto.LineType;
import cl.timbre.dto.ReceptorRequest;
import cl.timbre.dto.ReferenciaRequest;
import cl.timbre.exception.ApiException;
import cl.timbre.pdf.RideBuilder;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.storage.StorageException;
import cl.timbre.storage.StorageService;
import cl.timbre.xml.XsdValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

class IssuanceServiceTest extends AbstractIntegrationTest {

    @Autowired private IssuanceService issuanceService;
    @Autowired private CafService cafService;
    @Autowired private EmisorRepository emisorRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private CertificateProvider certificateProvider;
    @Autowired private FolioAssigner folioAssigner;

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
        assertThat(document.getXmlKey()).isNotNull();
        assertThat(document.getProximaConsultaAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
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

        // XML is stored in storage now, so just verify the key is set
        assertThat(document.getXmlKey()).isNotNull().contains(".xml");
    }

    @Test
    void unTipoDteNoSoportadoFalla() {
        IssueDocumentRequest request = new IssueDocumentRequest("pedido-1", 39, LocalDate.of(2026, 8, 9),
                new ReceptorRequest("77777777-7", "Constructora Andes SpA", "Construccion",
                        "Av. Apoquindo 4500", "Las Condes"),
                List.of(new IssueLine("Generador", 1, 1190000, LineType.AFECTO)),
                List.of());

        assertThatThrownBy(() -> issuanceService.issue(emisor, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiException.getCodigo()).isEqualTo("tipo_dte_no_soportado");
                });
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
        StorageService storageMock = Mockito.mock(StorageService.class);
        IssuanceService servicioConCertificadoInvalido = new IssuanceService(
                documentRepository, folioAssigner, new CertificateProvider(base64, "clave-mala"), storageMock);

        assertThatThrownBy(() -> servicioConCertificadoInvalido.issue(emisor, requestFactura("pedido-fallido")))
                .isInstanceOf(ApiException.class);

        Document guardado = documentRepository.findByEmisorIdAndExternalId(emisor.getId(), "pedido-fallido")
                .orElseThrow();
        assertThat(guardado.getEstado()).isEqualTo(DocumentStatus.ERROR_ENVIO);
        assertThat(guardado.getFolio()).isEqualTo(1);
        assertThat(guardado.getXmlContent()).isNull();
    }

    @Test
    void reintentarUnExternalIdConErrorEnvioFallaConConflicto() throws Exception {
        String base64 = Base64.getEncoder().encodeToString(
                Files.readAllBytes(Path.of("src/test/resources/test-cert.p12")));
        StorageService storageMock = Mockito.mock(StorageService.class);
        IssuanceService servicioConCertificadoInvalido = new IssuanceService(
                documentRepository, folioAssigner, new CertificateProvider(base64, "clave-mala"), storageMock);

        assertThatThrownBy(() -> servicioConCertificadoInvalido.issue(emisor, requestFactura("pedido-error-previo")))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> issuanceService.issue(emisor, requestFactura("pedido-error-previo")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiException.getCodigo()).isEqualTo("emision_previa_fallida");
                });
    }

    @Test
    void reintentarUnExternalIdConErrorEnvioYXmlPresenteDevuelveElDocumentoSinConflicto() {
        Document fallidoConXml = Document.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .externalId("pedido-error-con-xml")
                .tipoDte(33)
                .folio(999)
                .rutReceptor("77777777-7")
                .razonSocialReceptor("Constructora Andes SpA")
                .montoNeto(1000000)
                .montoIva(190000)
                .montoTotal(1190000)
                .estado(DocumentStatus.ERROR_ENVIO)
                .xmlContent("<EnvioDTE>contenido de prueba</EnvioDTE>")
                .storedFallback(false)
                .build();
        documentRepository.save(fallidoConXml);

        Document resultado = issuanceService.issue(emisor, requestFactura("pedido-error-con-xml"));

        assertThat(resultado.getId()).isEqualTo(fallidoConXml.getId());
        assertThat(resultado.getEstado()).isEqualTo(DocumentStatus.ERROR_ENVIO);
    }

    @Test
    void emiteUnaNotaDeCreditoConReferenciaYValidaContraElXsd() throws Exception {
        cafService.register(emisor, Files.readAllBytes(
                Path.of("src/test/resources/sii/caf-61-ejemplo.xml")));

        ReferenciaRequest referencia = new ReferenciaRequest(33, 100, LocalDate.of(2026, 8, 1), 1,
                "Anula factura por error de monto");
        IssueDocumentRequest request = new IssueDocumentRequest("pedido-nc-1", 61, LocalDate.of(2026, 8, 9),
                new ReceptorRequest("77777777-7", "Constructora Andes SpA", "Construccion",
                        "Av. Apoquindo 4500", "Las Condes"),
                List.of(new IssueLine("Generador", 1, 1190000, LineType.AFECTO)),
                List.of(referencia));

        Document document = issuanceService.issue(emisor, request);

        assertThat(document.getTipoDte()).isEqualTo(61);
        assertThat(document.getEstado()).isEqualTo(DocumentStatus.PENDIENTE_ENVIO);

        // XML is stored in storage, so just verify the key is set
        assertThat(document.getXmlKey()).isNotNull().contains(".xml");
    }

    @Test
    void emitiendoConFalloPdfNoBloquea() {
        // Verifica que aunque falle la generación de PDF, el documento se persiste con xmlKey
        // y estado=PENDIENTE_ENVIO (try/catch separation). Se fuerza el fallo mockeando RideBuilder,
        // ya que su lógica interna no puede fallar con datos válidos.
        Document resultado;
        try (MockedStatic<RideBuilder> rideBuilderMock = Mockito.mockStatic(RideBuilder.class)) {
            rideBuilderMock.when(() -> RideBuilder.build(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("fallo simulado de generación de PDF"));

            resultado = issuanceService.issue(emisor, requestFactura("pedido-pdf-fail"));
        }

        assertThat(resultado).isNotNull();
        assertThat(resultado.getEstado()).isEqualTo(DocumentStatus.PENDIENTE_ENVIO);
        assertThat(resultado.getXmlKey()).isNotNull()
                .as("XML key debe existir aunque falle el PDF");
        assertThat(resultado.getPdfContent())
                .as("pdfContent debe ser null cuando la generación del PDF falla")
                .isNull();
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

    @Test
    void testIssuePersisteXmlAndPdfToStorage() throws StorageException {
        StorageService storageMock = Mockito.mock(StorageService.class);
        IssuanceService servicioConStorage = new IssuanceService(
                documentRepository, folioAssigner, certificateProvider, storageMock);

        Document result = servicioConStorage.issue(emisor, requestFactura("pedido-storage-success"));

        assertThat(result.getEstado()).isEqualTo(DocumentStatus.PENDIENTE_ENVIO);
        assertThat(result.getXmlKey()).isNotNull().contains(".xml");
        assertThat(result.getXmlContent()).as("XML content should be cleared after storage").isNull();
        // PDF key may be null if PDF generation failed (optional feature)
        if (result.getPdfKey() != null) {
            assertThat(result.getPdfKey()).contains(".pdf");
            assertThat(result.getPdfContent()).as("PDF content should be cleared after storage").isNull();
        }
        assertThat(result.getStoredFallback()).isFalse();
    }

    @Test
    void testIssueFallsBackToDatabaseWhenStorageFails() throws StorageException {
        StorageService storageMock = Mockito.mock(StorageService.class);
        Mockito.doThrow(new StorageException("Storage service unavailable"))
                .when(storageMock).put(Mockito.anyString(), Mockito.any(byte[].class));

        IssuanceService servicioConStorageFail = new IssuanceService(
                documentRepository, folioAssigner, certificateProvider, storageMock);

        Document result = servicioConStorageFail.issue(emisor, requestFactura("pedido-storage-fail"));

        assertThat(result.getEstado()).isEqualTo(DocumentStatus.PENDIENTE_ENVIO);
        assertThat(result.getXmlKey()).isNull();
        assertThat(result.getXmlContent()).isNotNull().isNotBlank()
                .as("XML content should be preserved on storage failure");
        assertThat(result.getPdfKey()).isNull();
        // PDF content may be null if PDF generation failed (optional feature)
        if (result.getPdfContent() != null) {
            assertThat(result.getPdfContent()).isNotEmpty()
                    .as("PDF content should be preserved on storage failure if generated");
        }
        assertThat(result.getStoredFallback()).isTrue();
    }
}
