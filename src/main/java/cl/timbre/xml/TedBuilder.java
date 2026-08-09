package cl.timbre.xml;

import cl.timbre.model.DteDocument;
import cl.timbre.model.DteLine;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Timbre electrónico. El <DD> reúne los datos que el SII contrasta contra el
 * CAF, y <FRMT> es su firma con la llave privada del CAF — no con la del
 * certificado digital, que firma el documento completo más adelante.
 */
public final class TedBuilder {

    private TedBuilder() {}

    public static Element build(Document owner, DteDocument dte, String cafElementXml,
                                PrivateKey cafKey, LocalDateTime timestamp) {
        Element ted = owner.createElement("TED");
        ted.setAttribute("version", "1.0");

        Element dd = owner.createElement("DD");
        text(owner, dd, "RE", dte.emisor().getRut());
        text(owner, dd, "TD", String.valueOf(dte.tipoDte()));
        text(owner, dd, "F", String.valueOf(dte.folio()));
        text(owner, dd, "FE", dte.fechaEmision().format(DateTimeFormatter.ISO_LOCAL_DATE));
        text(owner, dd, "RR", dte.receptor().rut());
        text(owner, dd, "RSR", trunc(dte.receptor().razonSocial(), 40));
        text(owner, dd, "MNT", String.valueOf(dte.totales().montoTotal()));
        text(owner, dd, "IT1", trunc(primerItem(dte), 40));
        dd.appendChild(importCaf(owner, cafElementXml));
        text(owner, dd, "TSTED", timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        ted.appendChild(dd);

        Element frmt = owner.createElement("FRMT");
        frmt.setAttribute("algoritmo", "SHA1withRSA");
        frmt.setTextContent(sign(dd, cafKey));
        ted.appendChild(frmt);

        return ted;
    }

    private static String primerItem(DteDocument dte) {
        return dte.totales().lineas().stream()
                .findFirst()
                .map(DteLine::descripcion)
                .orElse("");
    }

    /** Reinserta el <CAF> original sin reformatearlo: su firma depende de los bytes exactos. */
    private static Node importCaf(Document owner, String cafElementXml) {
        Document parsed = XmlUtil.parse(cafElementXml.getBytes(StandardCharsets.ISO_8859_1));
        return cloneSinNamespace(owner, parsed.getDocumentElement());
    }

    /**
     * owner.importNode preservaria el namespace nulo explicito que le asigna un
     * DocumentBuilder namespace-aware al parsear el CAF original (que no declara
     * ningun xmlns): al insertarlo dentro de un <Documento> con namespace por
     * defecto, el serializador tendria que agregar xmlns="" para representar ese
     * nulo, lo que el XSD real del SII (elementFormDefault="qualified") rechaza.
     * Reconstruir con createElement (sin namespace, al igual que el resto de
     * TED/DD) hace que el CAF herede el namespace ambiente de donde se lo
     * inserte, sin tocar el contenido textual.
     */
    private static Element cloneSinNamespace(Document owner, Element original) {
        Element copia = owner.createElement(original.getTagName());
        NamedNodeMap atributos = original.getAttributes();
        for (int i = 0; i < atributos.getLength(); i++) {
            Node atributo = atributos.item(i);
            copia.setAttribute(atributo.getNodeName(), atributo.getNodeValue());
        }
        NodeList hijos = original.getChildNodes();
        for (int i = 0; i < hijos.getLength(); i++) {
            Node hijo = hijos.item(i);
            if (hijo.getNodeType() == Node.ELEMENT_NODE) {
                copia.appendChild(cloneSinNamespace(owner, (Element) hijo));
            } else {
                copia.appendChild(owner.importNode(hijo, true));
            }
        }
        return copia;
    }

    private static String sign(Element dd, PrivateKey cafKey) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(cafKey);
            // ISO-8859-1: es la codificación en que se enviará el documento.
            signature.update(XmlUtil.serialize(dd).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el timbre electronico", e);
        }
    }

    private static void text(Document owner, Element parent, String tag, String value) {
        Element element = owner.createElement(tag);
        element.setTextContent(value);
        parent.appendChild(element);
    }

    private static String trunc(String value, int max) {
        if (value == null) {
            return "";
        }
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
