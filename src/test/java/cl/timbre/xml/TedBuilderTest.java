package cl.timbre.xml;

import cl.timbre.TestFixtures;
import cl.timbre.caf.Caf;
import cl.timbre.caf.CafParser;
import cl.timbre.calc.TotalsCalculator;
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
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TedBuilderTest {

    private Caf caf;
    private DteDocument dte;

    @BeforeEach
    void setUp() throws Exception {
        caf = CafParser.parse(Files.readAllBytes(
                Path.of("src/test/resources/sii/caf-33-ejemplo.xml")));
        dte = new DteDocument(
                TestFixtures.emisor(),
                new DteReceptor("77777777-7", "Constructora Andes SpA",
                        "Construccion", "Av. Apoquindo 4500", "Las Condes"),
                33, 1042, LocalDate.of(2026, 8, 6),
                TotalsCalculator.compute(List.of(
                        new IssueLine("Generador Hyundai HY9000", 1, 1190000, LineType.AFECTO))),
                List.of());
    }

    private Element buildTed() {
        Document owner = XmlUtil.parse("<root/>".getBytes());
        return TedBuilder.build(owner, dte, caf.cafElementXml(),
                CafParser.privateKey(caf), LocalDateTime.of(2026, 8, 6, 10, 30, 0));
    }

    @Test
    void elDdLlevaLosDatosQueElSiiVerifica() {
        Element ted = buildTed();
        String xml = XmlUtil.serialize(ted);

        assertThat(xml).contains("<RE>76123456-0</RE>");
        assertThat(xml).contains("<TD>33</TD>");
        assertThat(xml).contains("<F>1042</F>");
        assertThat(xml).contains("<FE>2026-08-06</FE>");
        assertThat(xml).contains("<RR>77777777-7</RR>");
        assertThat(xml).contains("<MNT>1190000</MNT>");
        assertThat(xml).contains("<IT1>Generador Hyundai HY9000</IT1>");
        assertThat(xml).contains("<TSTED>2026-08-06T10:30:00</TSTED>");
    }

    @Test
    void embebeElCafCompleto() {
        assertThat(XmlUtil.serialize(buildTed())).contains("<CAF version=\"1.0\">");
    }

    @Test
    void laFirmaDelTedValidaConLaLlavePublicaDelCaf() throws Exception {
        Element ted = buildTed();
        Element dd = (Element) ted.getElementsByTagName("DD").item(0);
        String frmt = ted.getElementsByTagName("FRMT").item(0).getTextContent();

        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(publicKeyDelCaf());
        verifier.update(XmlUtil.serialize(dd).getBytes(StandardCharsets.ISO_8859_1));

        assertThat(verifier.verify(Base64.getMimeDecoder().decode(frmt))).isTrue();
    }

    @Test
    void elAlgoritmoDeclaradoEsElQueExigeElSii() {
        Element ted = buildTed();
        Element frmt = (Element) ted.getElementsByTagName("FRMT").item(0);

        assertThat(frmt.getAttribute("algoritmo")).isEqualTo("SHA1withRSA");
    }

    private PublicKey publicKeyDelCaf() throws Exception {
        String pem = Files.readString(Path.of("src/test/resources/sii/caf-33-ejemplo.xml"));
        String base64 = pem.substring(pem.indexOf("<RSAPUBK>") + 9, pem.indexOf("</RSAPUBK>"))
                .replaceAll("-----(BEGIN|END)[^-]+-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}
