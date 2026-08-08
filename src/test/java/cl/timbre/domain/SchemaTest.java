package cl.timbre.domain;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.repository.ApiKeyRepository;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.repository.FolioRangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaTest extends AbstractIntegrationTest {

    @Autowired
    private EmisorRepository emisorRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private FolioRangeRepository folioRangeRepository;

    @BeforeEach
    void setUp() {
        // El contenedor Postgres es único y compartido por toda la suite (ver
        // AbstractIntegrationTest), y otras clases de test insertan un emisor con el
        // mismo rut de prueba (mas api_key/folio_range referenciandolo por FK). Se
        // limpia antes de cada test para no chocar con residuos.
        apiKeyRepository.deleteAll();
        folioRangeRepository.deleteAll();
        emisorRepository.deleteAll();
    }

    @Test
    void elEsquemaCalzaConLasEntidadesYPersisteUnEmisor() {
        Emisor emisor = Emisor.builder()
                .id(UUID.randomUUID().toString())
                .rut("76123456-0")
                .razonSocial("Volterra Equipos SpA")
                .giro("Venta de generadores electricos")
                .acteco(465100)
                .direccionOrigen("Av. Siempre Viva 742")
                .comunaOrigen("Santiago")
                .resolucionNumero(80)
                .resolucionFecha(LocalDate.of(2014, 8, 22))
                .ambiente(Ambiente.CERTIFICACION)
                .certEnvVar("SII_CERT_P12_BASE64")
                .build();

        emisorRepository.save(emisor);

        assertThat(emisorRepository.findByRut("76123456-0")).isPresent();
    }
}
