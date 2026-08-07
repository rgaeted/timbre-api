package cl.timbre.controller;

import cl.timbre.auth.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ApiKeyFilter es un Filter @Component: @WebMvcTest lo incluye en el slice aunque
    // solo se prueba HealthController. Se mockea su dependencia para poder construirlo;
    // shouldNotFilter() excluye /api/v1/health, así que nunca se invoca resolve().
    @MockBean
    private ApiKeyService apiKeyService;

    @Test
    void healthDevuelveOkYAmbiente() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.ambiente").value("CERTIFICACION"));
    }
}
