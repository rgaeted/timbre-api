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
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XmlSignerTest {

    private SigningMaterial material;

    @BeforeEach
    void setUp() throws Exception {
        String base64 = Base64.getEncoder().encodeToString(
                Files.readAllBytes(Path.of("src/test/resources/test-cert.p12")));
        material = new CertificateProvider(base64, "test123").forEmisor(TestFixtures.emisor());
    }

    private Document dteFirmado() throws Exception {
        DteDocument dte = new DteDocument(
                TestFixtures.emisor(),
                new DteReceptor("77777777-7", "Constructora Andes SpA",
                        "Construccion", "Av. Apoquindo 4500", "Las Condes"),
                33, 1042, LocalDate.of(2026, 8, 6),
                TotalsCalculator.compute(List.of(
                        new IssueLine("Generador", 1, 1190000, LineType.AFECTO))),
                List.of());

        Document doc = DteXmlBuilder.build(dte);
        Element documento = (Element) doc
                .getElementsByTagNameNS(DteXmlBuilder.NS, "Documento").item(0);

        // El XSD real del SII exige <TED> y <TmstFirma> dentro de <Documento>
        // (minOccurs por defecto = 1, sin excepcion). Sin ellos la validacion
        // contra DTE_v10.xsd falla aunque la firma en si sea correcta.
        Caf caf = CafParser.parse(Files.readAllBytes(
                Path.of("src/test/resources/sii/caf-33-ejemplo.xml")));
        Element ted = TedBuilder.build(doc, dte, caf.cafElementXml(),
                CafParser.privateKey(caf), LocalDateTime.of(2026, 8, 6, 10, 30, 0));
        documento.appendChild(ted);

        Element tmstFirma = doc.createElementNS(DteXmlBuilder.NS, "TmstFirma");
        tmstFirma.setTextContent("2026-08-06T10:30:00");
        documento.appendChild(tmstFirma);

        XmlSigner.sign(doc, doc.getDocumentElement(), "F1042T33", material);
        return doc;
    }

    @Test
    void agregaLaFirmaComoHijoDelDte() throws Exception {
        Document doc = dteFirmado();
        NodeList firmas = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");

        assertThat(firmas.getLength()).isEqualTo(1);
        assertThat(firmas.item(0).getParentNode().getLocalName()).isEqualTo("DTE");
    }

    @Test
    void laFirmaValidaContraElCertificado() throws Exception {
        Document doc = dteFirmado();
        Element signature = (Element) doc
                .getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);

        DOMValidateContext context = new DOMValidateContext(
                material.certificate().getPublicKey(), signature);
        // Sin esto el validador no resuelve la referencia "#F1042T33".
        context.setIdAttributeNS(
                (Element) doc.getElementsByTagNameNS(DteXmlBuilder.NS, "Documento").item(0),
                null, "ID");
        // JDK habilita "secure validation" por defecto en el validador, lo que
        // prohibe RSA-SHA1 aunque el SII lo exige. No aplica al firmado (arriba),
        // solo a esta verificacion de prueba.
        context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);

        XMLSignature parsed = XMLSignatureFactory.getInstance("DOM")
                .unmarshalXMLSignature(context);

        assertThat(parsed.validate(context)).isTrue();
    }

    @Test
    void alterarElDocumentoInvalidaLaFirma() throws Exception {
        Document doc = dteFirmado();
        doc.getElementsByTagNameNS(DteXmlBuilder.NS, "MntTotal").item(0)
                .setTextContent("1");

        Element signature = (Element) doc
                .getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
        DOMValidateContext context = new DOMValidateContext(
                material.certificate().getPublicKey(), signature);
        context.setIdAttributeNS(
                (Element) doc.getElementsByTagNameNS(DteXmlBuilder.NS, "Documento").item(0),
                null, "ID");
        context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);

        XMLSignature parsed = XMLSignatureFactory.getInstance("DOM")
                .unmarshalXMLSignature(context);

        assertThat(parsed.validate(context)).isFalse();
    }

    @Test
    void incluyeElCertificadoYLaLlavePublicaEnElKeyInfo() throws Exception {
        String xml = XmlUtil.serialize(dteFirmado());

        assertThat(xml).contains("X509Certificate");
        assertThat(xml).contains("RSAKeyValue");
    }

    @Test
    void elDocumentoFirmadoValidaContraElXsdDelSii() throws Exception {
        assertThat(XsdValidator.errores(XmlUtil.serializeBytes(dteFirmado()),
                "src/test/resources/sii/xsd/DTE_v10.xsd")).isEmpty();
    }

    @Test
    void firmaElDocumentoCompletoSinAtributoId() throws Exception {
        Document doc = XmlUtil.parse("<getToken><item><Semilla>123456789</Semilla></item></getToken>".getBytes());

        XmlSigner.signWholeDocument(doc, doc.getDocumentElement(), material);

        NodeList firmas = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        assertThat(firmas.getLength()).isEqualTo(1);
        assertThat(firmas.item(0).getParentNode().getLocalName()).isEqualTo("getToken");
    }

    @Test
    void laFirmaDelDocumentoCompletoValidaContraElCertificado() throws Exception {
        Document doc = XmlUtil.parse("<getToken><item><Semilla>987654321</Semilla></item></getToken>".getBytes());

        XmlSigner.signWholeDocument(doc, doc.getDocumentElement(), material);

        Element signature = (Element) doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
        DOMValidateContext context = new DOMValidateContext(material.certificate().getPublicKey(), signature);
        context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);

        XMLSignature parsed = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);

        assertThat(parsed.validate(context)).isTrue();
    }
}
