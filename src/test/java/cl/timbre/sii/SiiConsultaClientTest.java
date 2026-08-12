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
