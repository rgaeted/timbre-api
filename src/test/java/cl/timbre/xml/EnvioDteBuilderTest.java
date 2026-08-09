package cl.timbre.xml;

import cl.timbre.TestFixtures;
import cl.timbre.caf.Caf;
import cl.timbre.caf.CafParser;
import cl.timbre.calc.TotalsCalculator;
import cl.timbre.cert.CertificateProvider;
import cl.timbre.cert.SigningMaterial;
import cl.timbre.dto.IssueLine;
import cl.timbre.dto.LineType;
import cl.timbre.model.DteDocument;
import cl.timbre.model.DteReceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnvioDteBuilderTest {

    private SigningMaterial material;

    @BeforeEach
    void setUp() throws Exception {
        String base64 = Base64.getEncoder().encodeToString(
                Files.readAllBytes(Path.of("src/test/resources/test-cert.p12")));
        material = new CertificateProvider(base64, "test123").forEmisor(TestFixtures.emisor());
    }

    private Document dteFirmado(int folio) throws Exception {
        DteDocument dte = new DteDocument(
                TestFixtures.emisor(),
                new DteReceptor("77777777-7", "Constructora Andes SpA",
                        "Construccion", "Av. Apoquindo 4500", "Las Condes"),
                33, folio, LocalDate.of(2026, 8, 6),
                TotalsCalculator.compute(List.of(
                        new IssueLine("Generador", 1, 1190000, LineType.AFECTO))),
                List.of());

        Document doc = DteXmlBuilder.build(dte);
        Element documento = (Element) doc
                .getElementsByTagNameNS(DteXmlBuilder.NS, "Documento").item(0);

        // El XSD real del SII exige <TED> y <TmstFirma> dentro de <Documento>
        // (ver Task 11): sin ellos, elSobreValidaContraElXsdDelSii falla la
        // validacion contra EnvioDTE_v10.xsd por elementos faltantes.
        Caf caf = CafParser.parse(Files.readAllBytes(
                Path.of("src/test/resources/sii/caf-33-ejemplo.xml")));
        Element ted = TedBuilder.build(doc, dte, caf.cafElementXml(),
                CafParser.privateKey(caf), LocalDateTime.of(2026, 8, 6, 10, 30, 0));
        documento.appendChild(ted);

        Element tmstFirma = doc.createElementNS(DteXmlBuilder.NS, "TmstFirma");
        tmstFirma.setTextContent("2026-08-06T10:30:00");
        documento.appendChild(tmstFirma);

        XmlSigner.sign(doc, doc.getDocumentElement(),
                DteXmlBuilder.documentId(33, folio), material);
        return doc;
    }

    private byte[] sobre() throws Exception {
        return EnvioDteBuilder.build(TestFixtures.emisor(),
                List.of(dteFirmado(1042)), material,
                LocalDateTime.of(2026, 8, 6, 10, 30, 0));
    }

    @Test
    void laCaratulaVaDirigidaAlSii() throws Exception {
        String xml = new String(sobre(), StandardCharsets.ISO_8859_1);

        assertThat(xml).contains("<RutEmisor>76123456-0</RutEmisor>");
        // 60803000-K es el RUT del SII como receptor del envio.
        assertThat(xml).contains("<RutReceptor>60803000-K</RutReceptor>");
    }

    @Test
    void declaraCuantosDocumentosDeCadaTipoVan() throws Exception {
        String xml = new String(sobre(), StandardCharsets.ISO_8859_1);

        assertThat(xml).contains("<TpoDTE>33</TpoDTE>");
        assertThat(xml).contains("<NroDTE>1</NroDTE>");
    }

    @Test
    void seDeclaraEnIso88591() throws Exception {
        String xml = new String(sobre(), StandardCharsets.ISO_8859_1);

        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>");
    }

    @Test
    void elSobreValidaContraElXsdDelSii() throws Exception {
        assertThat(XsdValidator.errores(sobre(),
                "src/test/resources/sii/xsd/EnvioDTE_v10.xsd")).isEmpty();
    }

    @Test
    void conservaLaFirmaDeCadaDteAdemasDeLaDelSobre() throws Exception {
        String xml = new String(sobre(), StandardCharsets.ISO_8859_1);

        // Una firma por DTE mas la del SetDTE. Se cuenta "<Signature " (con
        // espacio) y no solo "<Signature" a secas: el elemento raiz siempre
        // trae al menos el atributo xmlns, pero "<SignatureMethod" y
        // "<SignatureValue" tambien empiezan con "<Signature" y contaminarian
        // el conteo si se buscara el prefijo a secas.
        assertThat(xml.split("<Signature ", -1).length - 1).isEqualTo(2);
    }
}
