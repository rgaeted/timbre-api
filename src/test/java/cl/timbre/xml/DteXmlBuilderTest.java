package cl.timbre.xml;

import cl.timbre.TestFixtures;
import cl.timbre.calc.TotalsCalculator;
import cl.timbre.dto.IssueLine;
import cl.timbre.dto.LineType;
import cl.timbre.model.DteDocument;
import cl.timbre.model.DteReceptor;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathFactory;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DteXmlBuilderTest {

    private String xpath(Document doc, String expr) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expr, doc);
    }

    private DteDocument factura() {
        var totales = TotalsCalculator.compute(List.of(
                new IssueLine("Generador Hyundai HY9000", 1, 899990, LineType.AFECTO),
                new IssueLine("Despacho", 1, 45000, LineType.AFECTO),
                new IssueLine("Cupon INVIERNO10", 1, -89999, LineType.DESCUENTO)));

        return new DteDocument(
                TestFixtures.emisor(),
                new DteReceptor("77777777-7", "Constructora Andes SpA",
                        "Construccion de edificios", "Av. Apoquindo 4500", "Las Condes"),
                33, 1042, LocalDate.of(2026, 8, 6), totales, List.of());
    }

    @Test
    void elIdDelDocumentoCombinaFolioYTipo() {
        assertThat(DteXmlBuilder.documentId(33, 1042)).isEqualTo("F1042T33");
    }

    @Test
    void escribeElEncabezadoCompleto() throws Exception {
        Document doc = DteXmlBuilder.build(factura());

        assertThat(xpath(doc, "//*[local-name()='TipoDTE']")).isEqualTo("33");
        assertThat(xpath(doc, "//*[local-name()='Folio']")).isEqualTo("1042");
        assertThat(xpath(doc, "//*[local-name()='FchEmis']")).isEqualTo("2026-08-06");
        assertThat(xpath(doc, "//*[local-name()='RUTEmisor']")).isEqualTo("76123456-0");
        assertThat(xpath(doc, "//*[local-name()='RUTRecep']")).isEqualTo("77777777-7");
        assertThat(xpath(doc, "//*[local-name()='TasaIVA']")).isEqualTo("19");
    }

    @Test
    void losTotalesCoincidenConElCalculo() throws Exception {
        DteDocument dte = factura();
        Document doc = DteXmlBuilder.build(dte);

        assertThat(xpath(doc, "//*[local-name()='MntNeto']"))
                .isEqualTo(String.valueOf(dte.totales().montoNeto()));
        assertThat(xpath(doc, "//*[local-name()='IVA']"))
                .isEqualTo(String.valueOf(dte.totales().iva()));
        assertThat(xpath(doc, "//*[local-name()='MntTotal']"))
                .isEqualTo(String.valueOf(dte.totales().montoTotal()));
    }

    @Test
    void elDescuentoSaleComoDscRcgGlobalYNoComoDetalle() throws Exception {
        Document doc = DteXmlBuilder.build(factura());

        assertThat(doc.getElementsByTagNameNS("http://www.sii.cl/SiiDte", "Detalle").getLength())
                .isEqualTo(2);
        assertThat(xpath(doc, "//*[local-name()='DscRcgGlobal'][1]/*[local-name()='TpoMov']"))
                .isEqualTo("D");
        assertThat(xpath(doc, "//*[local-name()='DscRcgGlobal'][1]/*[local-name()='TpoValor']"))
                .isEqualTo("$");
    }

    @Test
    void elDocumentoLlevaElIdQueLaFirmaVaAReferenciar() throws Exception {
        Document doc = DteXmlBuilder.build(factura());

        assertThat(xpath(doc, "//*[local-name()='Documento']/@ID")).isEqualTo("F1042T33");
    }

    @Test
    void truncaLosCamposQueElSiiLimita() throws Exception {
        DteDocument dte = new DteDocument(
                TestFixtures.emisor(),
                new DteReceptor("77777777-7",
                        "Razon social larguisima que supera con creces el limite de cien caracteres "
                                + "impuesto por el servicio de impuestos internos de Chile",
                        "Giro", "Direccion", "Comuna"),
                33, 1, LocalDate.now(),
                TotalsCalculator.compute(List.of(
                        new IssueLine("Item", 1, 1190, LineType.AFECTO))),
                List.of());

        Document doc = DteXmlBuilder.build(dte);

        assertThat(xpath(doc, "//*[local-name()='RznSocRecep']").length()).isLessThanOrEqualTo(100);
    }
}
