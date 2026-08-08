package cl.timbre.auth;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.domain.Emisor;
import cl.timbre.repository.ApiKeyRepository;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.repository.FolioRangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ApiKeyFilterTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApiKeyService apiKeyService;
    @Autowired private EmisorRepository emisorRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private FolioRangeRepository folioRangeRepository;

    private String plainKey;

    @BeforeEach
    void setUp() {
        apiKeyRepository.deleteAll();
        folioRangeRepository.deleteAll();
        emisorRepository.deleteAll();
        Emisor emisor = emisorRepository.save(TestFixtures.emisor());
        plainKey = apiKeyService.generate(emisor.getId(), "test").plainKey();
    }

    @Test
    void healthNoRequiereKey() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }

    @Test
    void sinKeyDevuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/documents")).andExpect(status().isUnauthorized());
    }

    @Test
    void conKeyInvalidaDevuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/documents").header("Authorization", "Bearer tmb_live_falsa"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conKeyValidaNoDevuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/documents").header("Authorization", "Bearer " + plainKey))
                .andExpect(status().isNotFound());
    }
}
