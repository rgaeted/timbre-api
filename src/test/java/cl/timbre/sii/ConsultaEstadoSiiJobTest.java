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
