package cl.timbre.cert;

import cl.timbre.TestFixtures;
import cl.timbre.domain.Emisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CertificateProviderTest {

    private CertificateProvider provider;
    private Emisor emisor;

    @BeforeEach
    void setUp() throws Exception {
        byte[] p12 = Files.readAllBytes(Path.of("src/test/resources/test-cert.p12"));
        String base64 = Base64.getEncoder().encodeToString(p12);
        provider = new CertificateProvider(base64, "test123");
        emisor = TestFixtures.emisor();
    }

    @Test
    void cargaLaLlavePrivadaYElCertificado() {
        SigningMaterial material = provider.forEmisor(emisor);

        assertThat(material.privateKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(material.certificate().getSubjectX500Principal().getName())
                .contains("CN=Timbre Test");
    }

    @Test
    void exponeLaFechaDeExpiracion() {
        assertThat(provider.expiresAt(emisor)).isAfter(Instant.now());
    }

    @Test
    void unaClaveIncorrectaFallaConMensajeClaro() {
        CertificateProvider malo = new CertificateProvider(provider.rawBase64(), "clave-mala");

        assertThatThrownBy(() -> malo.forEmisor(emisor))
                .hasMessageContaining("certificado");
    }

    @Test
    void elMensajeDeErrorNoFiltraLaClave() {
        CertificateProvider malo = new CertificateProvider(provider.rawBase64(), "s3cr3t-clave");

        assertThatThrownBy(() -> malo.forEmisor(emisor))
                .hasMessageNotContaining("s3cr3t-clave");
    }
}
