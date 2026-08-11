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

class SiiUploadClientTest {

    private MockWebServer server;
    private SiiUploadClient client;
    private Emisor emisor;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        SiiProperties properties = new SiiProperties("CERTIFICACION", "", "", 5000,
                "http://localhost:" + server.getPort(), 10, 300000, 60000, 600000);

        client = new SiiUploadClient(new SiiUrlResolver(properties), properties);
        emisor = TestFixtures.emisor();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void unaSubidaExitosaDevuelveElTrackId() {
        server.enqueue(new MockResponse().setBody("<UPLOAD><STATUS>0</STATUS><TRACKID>123456789</TRACKID></UPLOAD>"));

        SiiUploadClient.ResultadoSubida resultado = client.subir(
                emisor, new SiiAuthClient.Token("token-de-prueba"), "<EnvioDTE>contenido</EnvioDTE>", "F1T33.xml");

        assertThat(resultado.exitoso()).isTrue();
        assertThat(resultado.trackId()).isEqualTo("123456789");
    }

    @Test
    void unRechazoDelSiiNoEsExitoso() {
        server.enqueue(new MockResponse().setBody("<UPLOAD><STATUS>2</STATUS></UPLOAD>"));

        SiiUploadClient.ResultadoSubida resultado = client.subir(
                emisor, new SiiAuthClient.Token("token-de-prueba"), "<EnvioDTE>contenido</EnvioDTE>", "F1T33.xml");

        assertThat(resultado.exitoso()).isFalse();
        assertThat(resultado.detalle()).contains("2");
    }

    @Test
    void elTokenViajaComoCookie() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("<UPLOAD><STATUS>0</STATUS><TRACKID>1</TRACKID></UPLOAD>"));

        client.subir(emisor, new SiiAuthClient.Token("mi-token"), "<EnvioDTE/>", "F1T33.xml");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Cookie")).isEqualTo("TOKEN=mi-token");
    }
}
