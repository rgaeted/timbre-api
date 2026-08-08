package cl.timbre.xml;

import cl.timbre.model.DteDocument;
import cl.timbre.model.DteGlobalDiscount;
import cl.timbre.model.DteLine;
import cl.timbre.model.DteReference;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.time.format.DateTimeFormatter;

/**
 * Arma el DOM del DTE. No firma ni timbra: eso lo hacen TedBuilder y XmlSigner
 * sobre el documento que sale de aquí.
 *
 * El ORDEN de los elementos importa: el XSD del SII usa xs:sequence, así que un
 * elemento fuera de lugar invalida el documento aunque el contenido sea correcto.
 */
public final class DteXmlBuilder {

    public static final String NS = "http://www.sii.cl/SiiDte";
    private static final int TASA_IVA = 19;

    private DteXmlBuilder() {}

    public static String documentId(int tipoDte, int folio) {
        return "F" + folio + "T" + tipoDte;
    }

    public static Document build(DteDocument dte) {
        Document doc = newDocument();

        Element root = doc.createElementNS(NS, "DTE");
        root.setAttribute("version", "1.0");
        doc.appendChild(root);

        Element documento = doc.createElementNS(NS, "Documento");
        documento.setAttribute("ID", documentId(dte.tipoDte(), dte.folio()));
        root.appendChild(documento);

        documento.appendChild(encabezado(doc, dte));
        for (DteLine linea : dte.totales().lineas()) {
            documento.appendChild(detalle(doc, linea));
        }
        for (DteGlobalDiscount descuento : dte.totales().descuentos()) {
            documento.appendChild(dscRcgGlobal(doc, descuento));
        }
        for (DteReference referencia : dte.referencias()) {
            documento.appendChild(referencia(doc, referencia));
        }
        // El TED y el TmstFirma los inserta el IssuanceService, en ese orden y
        // siempre al final de <Documento>.
        return doc;
    }

    private static Element encabezado(Document doc, DteDocument dte) {
        Element encabezado = doc.createElementNS(NS, "Encabezado");

        Element idDoc = doc.createElementNS(NS, "IdDoc");
        text(doc, idDoc, "TipoDTE", String.valueOf(dte.tipoDte()));
        text(doc, idDoc, "Folio", String.valueOf(dte.folio()));
        text(doc, idDoc, "FchEmis", dte.fechaEmision().format(DateTimeFormatter.ISO_LOCAL_DATE));
        encabezado.appendChild(idDoc);

        Element emisor = doc.createElementNS(NS, "Emisor");
        text(doc, emisor, "RUTEmisor", dte.emisor().getRut());
        text(doc, emisor, "RznSoc", trunc(dte.emisor().getRazonSocial(), 100));
        text(doc, emisor, "GiroEmis", trunc(dte.emisor().getGiro(), 80));
        text(doc, emisor, "Acteco", String.valueOf(dte.emisor().getActeco()));
        text(doc, emisor, "DirOrigen", trunc(dte.emisor().getDireccionOrigen(), 60));
        text(doc, emisor, "CmnaOrigen", trunc(dte.emisor().getComunaOrigen(), 20));
        encabezado.appendChild(emisor);

        Element receptor = doc.createElementNS(NS, "Receptor");
        text(doc, receptor, "RUTRecep", dte.receptor().rut());
        text(doc, receptor, "RznSocRecep", trunc(dte.receptor().razonSocial(), 100));
        text(doc, receptor, "GiroRecep", trunc(dte.receptor().giro(), 40));
        text(doc, receptor, "DirRecep", trunc(dte.receptor().direccion(), 70));
        text(doc, receptor, "CmnaRecep", trunc(dte.receptor().comuna(), 20));
        encabezado.appendChild(receptor);

        Element totales = doc.createElementNS(NS, "Totales");
        text(doc, totales, "MntNeto", String.valueOf(dte.totales().montoNeto()));
        text(doc, totales, "TasaIVA", String.valueOf(TASA_IVA));
        text(doc, totales, "IVA", String.valueOf(dte.totales().iva()));
        text(doc, totales, "MntTotal", String.valueOf(dte.totales().montoTotal()));
        encabezado.appendChild(totales);

        return encabezado;
    }

    private static Element detalle(Document doc, DteLine linea) {
        Element detalle = doc.createElementNS(NS, "Detalle");
        text(doc, detalle, "NroLinDet", String.valueOf(linea.numero()));
        text(doc, detalle, "NmbItem", trunc(linea.descripcion(), 80));
        text(doc, detalle, "QtyItem", String.valueOf(linea.cantidad()));
        text(doc, detalle, "PrcItem", String.valueOf(linea.precioUnitarioNeto()));
        text(doc, detalle, "MontoItem", String.valueOf(linea.montoNeto()));
        return detalle;
    }

    private static Element dscRcgGlobal(Document doc, DteGlobalDiscount descuento) {
        Element dr = doc.createElementNS(NS, "DscRcgGlobal");
        text(doc, dr, "NroLinDR", String.valueOf(descuento.numero()));
        text(doc, dr, "TpoMov", String.valueOf(descuento.tipoMovimiento()));
        text(doc, dr, "GlosaDR", trunc(descuento.glosa(), 45));
        text(doc, dr, "TpoValor", "$");
        text(doc, dr, "ValorDR", String.valueOf(descuento.valor()));
        return dr;
    }

    private static Element referencia(Document doc, DteReference ref) {
        Element referencia = doc.createElementNS(NS, "Referencia");
        text(doc, referencia, "NroLinRef", String.valueOf(ref.numero()));
        text(doc, referencia, "TpoDocRef", String.valueOf(ref.tipoDocRef()));
        text(doc, referencia, "FolioRef", String.valueOf(ref.folioRef()));
        text(doc, referencia, "FchRef", ref.fechaRef().format(DateTimeFormatter.ISO_LOCAL_DATE));
        text(doc, referencia, "CodRef", String.valueOf(ref.codigoRef()));
        text(doc, referencia, "RazonRef", trunc(ref.razonRef(), 90));
        return referencia;
    }

    private static void text(Document doc, Element parent, String tag, String value) {
        Element element = doc.createElementNS(NS, tag);
        element.setTextContent(value);
        parent.appendChild(element);
    }

    /** El SII rechaza campos más largos que su límite; truncar es preferible a fallar. */
    private static String trunc(String value, int max) {
        if (value == null) {
            return "";
        }
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static Document newDocument() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().newDocument();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo crear el documento XML", e);
        }
    }
}
