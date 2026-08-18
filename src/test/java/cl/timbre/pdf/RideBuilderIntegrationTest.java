package cl.timbre.pdf;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.caf.CafService;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.domain.Document;
import cl.timbre.dto.IssueDocumentRequest;
import cl.timbre.dto.IssueLine;
import cl.timbre.dto.LineType;
import cl.timbre.dto.ReceptorRequest;
import cl.timbre.issue.IssuanceService;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.repository.EmisorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RideBuilderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IssuanceService issuanceService;

    @Autowired
    private CafService cafService;

    @Autowired
    private EmisorRepository emisorRepository;

    @Autowired
    private DocumentRepository documentRepository;

    private Emisor emisor;

    @BeforeEach
    void setUp() throws Exception {
        emisor = emisorRepository.save(TestFixtures.emisor());
        cafService.register(emisor, Files.readAllBytes(
                Path.of("src/test/resources/sii/caf-33-ejemplo.xml")));
    }

    @Test
    void emisiónGeneraXmlYPdf() {
        IssueDocumentRequest request = new IssueDocumentRequest(
                "ext-123",
                33,
                LocalDate.now(),
                new ReceptorRequest("77.888.999-0", "Cliente", "Comercio", "Dirección", "Santiago"),
                List.of(new IssueLine("Servicio", 1, 50000, LineType.AFECTO)),
                List.of()
        );

        Document doc = issuanceService.issue(emisor, request);

        assertNotNull(doc.getId());
        assertEquals(DocumentStatus.PENDIENTE_ENVIO, doc.getEstado());
        // Desde fase D, un put() a storage exitoso limpia xmlContent/pdfContent y deja
        // la clave en xmlKey/pdfKey en su lugar -- solo cae a la columna BYTEA si
        // storage falla. Cualquiera de las dos formas cuenta como "persistido".
        assertTrue(doc.getXmlContent() != null || doc.getXmlKey() != null, "XML debe ser persistido");
        assertTrue(doc.getPdfContent() != null || doc.getPdfKey() != null, "PDF debe ser persistido");
        if (doc.getPdfContent() != null) {
            assertNotEquals(0, doc.getPdfContent().length);
        }
    }
}
