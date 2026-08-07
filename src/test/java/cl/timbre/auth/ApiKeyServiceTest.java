package cl.timbre.auth;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.domain.Emisor;
import cl.timbre.repository.ApiKeyRepository;
import cl.timbre.repository.EmisorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyServiceTest extends AbstractIntegrationTest {

    @Autowired private ApiKeyService apiKeyService;
    @Autowired private EmisorRepository emisorRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;

    private Emisor emisor;

    @BeforeEach
    void setUp() {
        apiKeyRepository.deleteAll();
        emisorRepository.deleteAll();
        emisor = emisorRepository.save(TestFixtures.emisor());
    }

    @Test
    void laKeyGeneradaResuelveAlEmisor() {
        ApiKeyService.GeneratedKey generada = apiKeyService.generate(emisor.getId(), "tienda prod");

        assertThat(generada.plainKey()).startsWith("tmb_");
        assertThat(apiKeyService.resolve(generada.plainKey()))
                .map(Emisor::getId)
                .contains(emisor.getId());
    }

    @Test
    void laKeyEnClaroNoSeGuardaEnLaBase() {
        ApiKeyService.GeneratedKey generada = apiKeyService.generate(emisor.getId(), "tienda prod");

        assertThat(apiKeyRepository.findAll())
                .noneMatch(k -> k.getHash().contains(generada.plainKey()));
    }

    @Test
    void unaKeyDesconocidaNoResuelve() {
        assertThat(apiKeyService.resolve("tmb_live_noexiste")).isEmpty();
    }

    @Test
    void unaKeyRevocadaNoResuelve() {
        ApiKeyService.GeneratedKey generada = apiKeyService.generate(emisor.getId(), "tienda prod");
        apiKeyService.revoke(generada.id());

        assertThat(apiKeyService.resolve(generada.plainKey())).isEmpty();
    }
}
