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
import cl.timbre.exception.ApiException;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.xml.XsdValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
