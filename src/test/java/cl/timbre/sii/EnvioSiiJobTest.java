package cl.timbre.sii;

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
import cl.timbre.issue.IssuanceService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EnvioSiiJobTest extends AbstractIntegrationTest {

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
        registry.add("sii.envio-max-intentos", () -> "2");
    }

    @AfterAll
    static void shutdownServer() throws IOException {
        SII.shutdown();
    }

    @Autowired private EnvioSiiJob envioSiiJob;
    @Autowired private IssuanceService issuanceService;
    @Autowired private CafService cafService;
    @Autowired private EmisorRepository emisorRepository;
    @Autowired private DocumentRepository documentRepository;

    private Emisor emisor;

    @BeforeEach
    void setUp() throws Exception {
        emisor = emisorRepository.save(TestFixtures.emisor());
        cafService.register(emisor, Files.readAllBytes(
                Path.of("src/test/resources/sii/caf-33-ejemplo.xml")));
    }

    private Document emitirFactura(String externalId) {
        IssueDocumentRequest request = new IssueDocumentRequest(externalId, 33, LocalDate.of(2026, 8, 9),
                new ReceptorRequest("77777777-7", "Constructora Andes SpA", "Construccion",
                        "Av. Apoquindo 4500", "Las Condes"),
                List.of(new IssueLine("Generador", 1, 1190000, LineType.AFECTO)),
                List.of());
        return issuanceService.issue(emisor, request);
    }

    private void encolarSemillaYToken() {
        SII.enqueue(new MockResponse()
                .setBody("<SII:RESPUESTA xmlns:SII=\"http://www.sii.cl/XMLSchema\">"
                        + "<SII:RESP_BODY><SEMILLA>111222333</SEMILLA></SII:RESP_BODY></SII:RESPUESTA>"));
        SII.enqueue(new MockResponse()
                .setBody("<SII:RESPUESTA xmlns:SII=\"http://www.sii.cl/XMLSchema\">"
                        + "<SII:RESP_BODY><TOKEN>token-de-prueba</TOKEN></SII:RESP_BODY></SII:RESPUESTA>"));
    }

    @Test
    void unDocumentoPendienteSeEnviaYQuedaEnviado() {
        Document emitido = emitirFactura("pedido-1");
        encolarSemillaYToken();
        SII.enqueue(new MockResponse()
                .setBody("<UPLOAD><STATUS>0</STATUS><TRACKID>555666777</TRACKID></UPLOAD>"));

        envioSiiJob.enviarPendientes();

        Document actualizado = documentRepository.findById(emitido.getId()).orElseThrow();
        assertThat(actualizado.getEstado()).isEqualTo(DocumentStatus.ENVIADO);
        assertThat(actualizado.getTrackId()).isEqualTo("555666777");
        assertThat(actualizado.getProximaConsultaAt()).isAfter(Instant.now());
    }

    @Test
    void unaFallaTransitoriaIncrementaIntentosYQuedaEnErrorEnvio() {
        Document emitido = emitirFactura("pedido-2");
        encolarSemillaYToken();
        SII.enqueue(new MockResponse().setResponseCode(500));

        envioSiiJob.enviarPendientes();

        Document actualizado = documentRepository.findById(emitido.getId()).orElseThrow();
        assertThat(actualizado.getEstado()).isEqualTo(DocumentStatus.ERROR_ENVIO);
        assertThat(actualizado.getIntentosConsulta()).isEqualTo(1);
        assertThat(actualizado.getProximaConsultaAt()).isAfter(Instant.now());
        assertThat(actualizado.getXmlContent()).isNotBlank();
    }

    @Test
    void unRechazoDefinitivoDejaDeReintentarse() {
        Document emitido = emitirFactura("pedido-3");
        encolarSemillaYToken();
        SII.enqueue(new MockResponse().setBody("<UPLOAD><STATUS>2</STATUS></UPLOAD>"));

        envioSiiJob.enviarPendientes();

        Document actualizado = documentRepository.findById(emitido.getId()).orElseThrow();
        assertThat(actualizado.getEstado()).isEqualTo(DocumentStatus.ERROR_ENVIO);
        assertThat(actualizado.getIntentosConsulta()).isEqualTo(2);
        assertThat(actualizado.getProximaConsultaAt()).isNull();
    }

    @Test
    void alcanzarElMaximoDeIntentosDejaDeReintentar() {
        Document emitido = emitirFactura("pedido-4");
        emitido.setIntentosConsulta(1);
        documentRepository.save(emitido);
        encolarSemillaYToken();
        SII.enqueue(new MockResponse().setResponseCode(500));

        envioSiiJob.enviarPendientes();

        Document actualizado = documentRepository.findById(emitido.getId()).orElseThrow();
        assertThat(actualizado.getIntentosConsulta()).isEqualTo(2);
        assertThat(actualizado.getProximaConsultaAt()).isNull();
    }

    @Test
    void reintentaUnErrorEnvioConXmlExistente() {
        Document fallidoConXml = Document.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .externalId("pedido-5")
                .tipoDte(33)
                .folio(999)
                .rutReceptor("77777777-7")
                .razonSocialReceptor("Constructora Andes SpA")
                .montoNeto(1000000)
                .montoIva(190000)
                .montoTotal(1190000)
                .estado(DocumentStatus.ERROR_ENVIO)
                .xmlContent("<EnvioDTE>contenido de prueba</EnvioDTE>")
                .intentosConsulta(1)
                .proximaConsultaAt(Instant.now().minusSeconds(60))
                .build();
        documentRepository.save(fallidoConXml);

        encolarSemillaYToken();
        SII.enqueue(new MockResponse()
                .setBody("<UPLOAD><STATUS>0</STATUS><TRACKID>888999000</TRACKID></UPLOAD>"));

        envioSiiJob.enviarPendientes();

        Document actualizado = documentRepository.findById(fallidoConXml.getId()).orElseThrow();
        assertThat(actualizado.getEstado()).isEqualTo(DocumentStatus.ENVIADO);
        assertThat(actualizado.getTrackId()).isEqualTo("888999000");
        assertThat(actualizado.getIntentosConsulta()).isZero();
    }

    @Test
    void unErrorEnvioSinXmlNuncaSeToca() {
        Document fallidoSinXml = Document.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .externalId("pedido-6")
                .tipoDte(33)
                .folio(998)
                .rutReceptor("77777777-7")
                .razonSocialReceptor("Constructora Andes SpA")
                .montoNeto(1000000)
                .montoIva(190000)
                .montoTotal(1190000)
                .estado(DocumentStatus.ERROR_ENVIO)
                .xmlContent(null)
                .proximaConsultaAt(Instant.now().minusSeconds(60))
                .build();
        documentRepository.save(fallidoSinXml);

        // SII es un MockWebServer estatico compartido por todos los metodos de esta
        // clase (arranca una sola vez), asi que getRequestCount() es acumulativo entre
        // tests -- el orden de ejecucion de JUnit no es alfabetico ni garantizado, por
        // lo que hay que comparar contra el conteo antes de esta llamada, no contra cero.
        int requestsAntes = SII.getRequestCount();

        envioSiiJob.enviarPendientes();

        assertThat(SII.getRequestCount()).isEqualTo(requestsAntes);
        Document sinCambios = documentRepository.findById(fallidoSinXml.getId()).orElseThrow();
        assertThat(sinCambios.getEstado()).isEqualTo(DocumentStatus.ERROR_ENVIO);
        assertThat(sinCambios.getXmlContent()).isNull();
    }
}
