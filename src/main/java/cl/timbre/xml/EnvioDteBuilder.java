package cl.timbre.xml;

import cl.timbre.cert.SigningMaterial;
import cl.timbre.domain.Emisor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sobre que agrupa uno o más DTE ya firmados, con carátula y firma propia. */
public final class EnvioDteBuilder {

    private static final String NS = DteXmlBuilder.NS;
    /** RUT del SII como receptor de todo envío de DTE. */
    private static final String RUT_SII = "60803000-K";
    private static final String SET_ID = "SetDoc";

    private EnvioDteBuilder() {}

    public static byte[] build(Emisor emisor, List<Document> dtesFirmados,
                               SigningMaterial material, LocalDateTime timestamp) {
        Document doc = newDocument();

        Element envio = doc.createElementNS(NS, "EnvioDTE");
        envio.setAttribute("version", "1.0");
        doc.appendChild(envio);

        Element setDte = doc.createElementNS(NS, "SetDTE");
        setDte.setAttribute("ID", SET_ID);
        envio.appendChild(setDte);

        setDte.appendChild(caratula(doc, emisor, dtesFirmados, timestamp));
        for (Document dte : dtesFirmados) {
            setDte.appendChild(doc.importNode(dte.getDocumentElement(), true));
        }

        XmlSigner.sign(doc, envio, SET_ID, material);

        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + XmlUtil.serialize(envio);
        return xml.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Element caratula(Document doc, Emisor emisor,
                                    List<Document> dtes, LocalDateTime timestamp) {
        Element caratula = doc.createElementNS(NS, "Caratula");
        caratula.setAttribute("version", "1.0");

        text(doc, caratula, "RutEmisor", emisor.getRut());
        text(doc, caratula, "RutEnvia", emisor.getRutEnvia());
        text(doc, caratula, "RutReceptor", RUT_SII);
        text(doc, caratula, "FchResol", emisor.getResolucionFecha()
                .format(DateTimeFormatter.ISO_LOCAL_DATE));
        text(doc, caratula, "NroResol", String.valueOf(emisor.getResolucionNumero()));
        text(doc, caratula, "TmstFirmaEnv", timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        for (Map.Entry<String, Integer> entry : contarPorTipo(dtes).entrySet()) {
            Element subTotal = doc.createElementNS(NS, "SubTotDTE");
            text(doc, subTotal, "TpoDTE", entry.getKey());
            text(doc, subTotal, "NroDTE", String.valueOf(entry.getValue()));
            caratula.appendChild(subTotal);
        }
        return caratula;
    }

    private static Map<String, Integer> contarPorTipo(List<Document> dtes) {
        Map<String, Integer> conteo = new LinkedHashMap<>();
        for (Document dte : dtes) {
            String tipo = dte.getElementsByTagNameNS(NS, "TipoDTE").item(0).getTextContent();
            conteo.merge(tipo, 1, Integer::sum);
        }
        return conteo;
    }

    private static void text(Document doc, Element parent, String tag, String value) {
        Element element = doc.createElementNS(NS, tag);
        element.setTextContent(value);
        parent.appendChild(element);
    }

    private static Document newDocument() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().newDocument();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo crear el sobre", e);
        }
    }
}
