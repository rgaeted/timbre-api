package cl.timbre.sii;

import cl.timbre.TestFixtures;
import cl.timbre.cert.CertificateProvider;
import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Emisor;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiiAuthClientTest {

    private MockWebServer server;
    private SiiAuthClient client;
    private Emisor emisor;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        String base64 = Base64.getEncoder().encodeToString(
                Files.readAllBytes(Path.of("src/test/resources/test-cert.p12")));
        CertificateProvider certificateProvider = new CertificateProvider(base64, "test123");

        SiiProperties properties = new SiiProperties("CERTIFICACION", "", "", 5000,
                "http://localhost:" + server.getPort(), 10, 300000, 60000, 600000);

        client = new SiiAuthClient(new SiiUrlResolver(properties), certificateProvider, properties);
        emisor = TestFixtures.emisor();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void encolarRespuestaSemilla(String semilla) {
        server.enqueue(new MockResponse()
                .setBody("<SII:RESPUESTA xmlns:SII=\"http://www.sii.cl/XMLSchema\">"
                        + "<SII:RESP_HDR><ESTADO>00</ESTADO></SII:RESP_HDR>"
                        + "<SII:RESP_BODY><SEMILLA>" + semilla + "</SEMILLA></SII:RESP_BODY></SII:RESPUESTA>")
                .addHeader("Content-Type", "text/xml"));
    }

    private void encolarRespuestaToken(String token) {
        server.enqueue(new MockResponse()
                .setBody("<SII:RESPUESTA xmlns:SII=\"http://www.sii.cl/XMLSchema\">"
                        + "<SII:RESP_HDR><ESTADO>00</ESTADO></SII:RESP_HDR>"
                        + "<SII:RESP_BODY><TOKEN>" + token + "</TOKEN></SII:RESP_BODY></SII:RESPUESTA>")
                .addHeader("Content-Type", "text/xml"));
    }

    @Test
    void obtieneUnTokenEncadenandoSemillaYCanje() {
        encolarRespuestaSemilla("123456789");
        encolarRespuestaToken("un-token-de-prueba");

        SiiAuthClient.Token token = client.obtenerToken(emisor);

        assertThat(token.valor()).isEqualTo("un-token-de-prueba");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void laSemillaFirmadaViajaEnElSegundoRequest() throws InterruptedException {
        encolarRespuestaSemilla("987654321");
        encolarRespuestaToken("otro-token");

        client.obtenerToken(emisor);

        server.takeRequest();
        RecordedRequest segundo = server.takeRequest();
        String bodyTexto = segundo.getBody().readUtf8();
        assertThat(bodyTexto).contains("987654321");
        assertThat(bodyTexto).contains("Signature");
    }

    @Test
    void unaRespuestaSinSemillaFalla() {
        server.enqueue(new MockResponse().setBody("<SII:RESPUESTA/>"));

        assertThatThrownBy(() -> client.obtenerToken(emisor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEMILLA");
    }

    @Test
    void elMensajeDeErrorNoIncluyeElCuerpoCrudoDeLaRespuesta() {
        server.enqueue(new MockResponse().setBody("<SII:RESPUESTA><TOKEN-SECRETO-QUE-NO-DEBE-APARECER/></SII:RESPUESTA>"));

        assertThatThrownBy(() -> client.obtenerToken(emisor))
                .hasMessageNotContaining("TOKEN-SECRETO-QUE-NO-DEBE-APARECER");
    }
}
