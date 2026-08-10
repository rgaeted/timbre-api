package cl.timbre.issue;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.caf.CafService;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.dto.IssueDocumentRequest;
import cl.timbre.dto.IssueLine;
import cl.timbre.dto.LineType;
import cl.timbre.dto.ReceptorRequest;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.xml.XsdValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IssuanceServiceTest extends AbstractIntegrationTest {

    @Autowired private IssuanceService issuanceService;
    @Autowired private CafService cafService;
    @Autowired private EmisorRepository emisorRepository;

    private Emisor emisor;

    @BeforeEach
    void setUp() throws Exception {
        emisor = emisorRepository.save(TestFixtures.emisor());
        cafService.register(emisor, Files.readAllBytes(
                Path.of("src/test/resources/sii/caf-33-ejemplo.xml")));
    }

    private IssueDocumentRequest requestFactura(String externalId) {
        return new IssueDocumentRequest(externalId, 33, LocalDate.of(2026, 8, 9),
                new ReceptorRequest("77777777-7", "Constructora Andes SpA", "Construccion",
                        "Av. Apoquindo 4500", "Las Condes"),
                List.of(new IssueLine("Generador", 1, 1190000, LineType.AFECTO)),
                List.of());
    }

    @Test
    void emiteUnaFacturaYQuedaPendienteDeEnvio() {
        Document document = issuanceService.issue(emisor, requestFactura("pedido-1"));

        assertThat(document.getFolio()).isEqualTo(1);
        assertThat(document.getEstado()).isEqualTo(DocumentStatus.PENDIENTE_ENVIO);
        assertThat(document.getMontoNeto()).isEqualTo(1000000);
        assertThat(document.getMontoIva()).isEqualTo(190000);
        assertThat(document.getMontoTotal()).isEqualTo(1190000);
        assertThat(document.getXmlContent()).isNotBlank();
    }

    @Test
    void elSegundoDocumentoUsaElSiguienteFolio() {
        issuanceService.issue(emisor, requestFactura("pedido-1"));
        Document segundo = issuanceService.issue(emisor, requestFactura("pedido-2"));

        assertThat(segundo.getFolio()).isEqualTo(2);
    }

    @Test
    void repetirElMismoExternalIdDevuelveElMismoDocumentoSinConsumirFolioNuevo() {
        Document primero = issuanceService.issue(emisor, requestFactura("pedido-repetido"));
        Document repetido = issuanceService.issue(emisor, requestFactura("pedido-repetido"));

        assertThat(repetido.getId()).isEqualTo(primero.getId());
        assertThat(repetido.getFolio()).isEqualTo(primero.getFolio());
    }

    @Test
    void elXmlGeneradoValidaContraElXsdDelSii() throws Exception {
        Document document = issuanceService.issue(emisor, requestFactura("pedido-1"));

        byte[] xml = document.getXmlContent().getBytes(StandardCharsets.ISO_8859_1);
        assertThat(XsdValidator.errores(xml, "src/test/resources/sii/xsd/EnvioDTE_v10.xsd")).isEmpty();
    }
}
