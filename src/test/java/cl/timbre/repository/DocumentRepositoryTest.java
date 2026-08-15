package cl.timbre.repository;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRepositoryTest extends AbstractIntegrationTest {

    @Autowired private DocumentRepository documentRepository;
    @Autowired private EmisorRepository emisorRepository;

    private Emisor emisor;

    @BeforeEach
    void setUp() {
        emisor = emisorRepository.save(TestFixtures.emisor());
    }

    @Test
    void persisteYRecuperaElXmlDelSobreFirmado() {
        Document document = Document.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .externalId("pedido-1")
                .tipoDte(33)
                .folio(1)
                .rutReceptor("77777777-7")
                .razonSocialReceptor("Constructora Andes SpA")
                .montoNeto(1000000)
                .montoIva(190000)
                .montoTotal(1190000)
                .estado(DocumentStatus.PENDIENTE_ENVIO)
                .xmlContent("<EnvioDTE>contenido de prueba</EnvioDTE>")
                .storedFallback(false)
                .build();

        documentRepository.save(document);

        Document recuperado = documentRepository
                .findByEmisorIdAndExternalId(emisor.getId(), "pedido-1")
                .orElseThrow();
        assertThat(recuperado.getXmlContent()).isEqualTo("<EnvioDTE>contenido de prueba</EnvioDTE>");
    }
}
