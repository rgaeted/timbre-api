package cl.timbre.issue;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.auth.ApiKeyService;
import cl.timbre.caf.CafService;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.repository.EmisorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class DocumentControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApiKeyService apiKeyService;
    @Autowired private EmisorRepository emisorRepository;
    @Autowired private CafService cafService;
    @Autowired private DocumentRepository documentRepository;

    private String apiKey;
    private Emisor emisor;

    private static final String CUERPO_VALIDO = """
            {
              "externalId": "pedido-8842",
              "tipoDte": 33,
              "fechaEmision": "2026-08-09",
              "receptor": {
                "rut": "77777777-7",
                "razonSocial": "Constructora Andes SpA",
                "giro": "Construccion",
                "direccion": "Av. Apoquindo 4500",
                "comuna": "Las Condes"
              },
              "lineas": [
                { "descripcion": "Generador", "cantidad": 1, "precioUnitarioBruto": 1190000, "tipo": "AFECTO" }
              ]
            }
            """;

    @BeforeEach
    void setUp() throws Exception {
        emisor = emisorRepository.save(TestFixtures.emisor());
        cafService.register(emisor, Files.readAllBytes(
                Path.of("src/test/resources/sii/caf-33-ejemplo.xml")));
        apiKey = apiKeyService.generate(emisor.getId(), "test").plainKey();
    }

    @Test
    void emitirUnDocumentoValidoDevuelve200ConElXmlBase64() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType("application/json")
                        .content(CUERPO_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folio").value(1))
                .andExpect(jsonPath("$.estado").value("PENDIENTE_ENVIO"))
                .andExpect(jsonPath("$.xmlBase64").isNotEmpty());
    }

    @Test
    void sinReceptorDevuelve400() throws Exception {
        String cuerpoSinReceptor = """
                {
                  "externalId": "pedido-8843",
                  "tipoDte": 33,
                  "fechaEmision": "2026-08-09",
                  "lineas": [
                    { "descripcion": "Generador", "cantidad": 1, "precioUnitarioBruto": 1190000, "tipo": "AFECTO" }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/documents")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType("application/json")
                        .content(cuerpoSinReceptor))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sinApiKeyDevuelve401() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .contentType("application/json")
                        .content(CUERPO_VALIDO))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getPdfRetorna200ConContenido() throws Exception {
        String docId = "doc-pdf-1";
        Document doc = Document.builder()
            .id(docId)
            .emisorId(emisor.getId())
            .externalId("ext-1")
            .tipoDte(33)
            .folio(1234)
            .rutReceptor("77.888.999-0")
            .razonSocialReceptor("Cliente Ltda")
            .montoNeto(100000)
            .montoIva(19000)
            .montoTotal(119000)
            .estado(DocumentStatus.PENDIENTE_ENVIO)
            .xmlContent("<xml>test</xml>")
            .pdfContent("PDF_BYTES".getBytes())
            .build();

        documentRepository.save(doc);

        mockMvc.perform(get("/api/v1/documents/" + docId + "/pdf")
                        .header("Authorization", "Bearer " + apiKey))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf"))
            .andExpect(content().bytes("PDF_BYTES".getBytes()));
    }

    @Test
    void getPdfRetorna404SiNoExiste() throws Exception {
        mockMvc.perform(get("/api/v1/documents/no-exist/pdf")
                        .header("Authorization", "Bearer " + apiKey))
            .andExpect(status().isNotFound());
    }

    @Test
    void getPdfRetorna409SiPdfEsNull() throws Exception {
        String docId = "doc-pdf-3";
        Document doc = Document.builder()
            .id(docId)
            .emisorId(emisor.getId())
            .externalId("ext-1")
            .tipoDte(33)
            .folio(1234)
            .rutReceptor("77.888.999-0")
            .razonSocialReceptor("Cliente Ltda")
            .montoNeto(100000)
            .montoIva(19000)
            .montoTotal(119000)
            .estado(DocumentStatus.PENDIENTE_ENVIO)
            .xmlContent("<xml>test</xml>")
            .pdfContent(null)  // PDF generation failed
            .build();

        documentRepository.save(doc);

        mockMvc.perform(get("/api/v1/documents/" + docId + "/pdf")
                        .header("Authorization", "Bearer " + apiKey))
            .andExpect(status().isConflict());
    }
}
