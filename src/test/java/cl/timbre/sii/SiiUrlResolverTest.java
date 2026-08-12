package cl.timbre.sii;

import cl.timbre.TestFixtures;
import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Emisor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiiUrlResolverTest {

    private final Emisor emisorCertificacion = TestFixtures.emisor();

    @Test
    void sinOverrideUsaLaUrlDelAmbienteDelEmisor() {
        SiiProperties properties = new SiiProperties(
                "CERTIFICACION", "", "", 5000, "", 10, 300000, 60000, 600000, 10, 300000, 60000);
        SiiUrlResolver resolver = new SiiUrlResolver(properties);

        assertThat(resolver.resolve(emisorCertificacion)).isEqualTo("https://maullin.sii.cl");
    }

    @Test
    void conOverrideUsaLaUrlConfigurada() {
        SiiProperties properties = new SiiProperties(
                "CERTIFICACION", "", "", 5000, "http://localhost:9999", 10, 300000, 60000, 600000, 10, 300000, 60000);
        SiiUrlResolver resolver = new SiiUrlResolver(properties);

        assertThat(resolver.resolve(emisorCertificacion)).isEqualTo("http://localhost:9999");
    }
}
