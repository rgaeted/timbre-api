package cl.timbre.issue;

import cl.timbre.caf.CafParser;
import cl.timbre.caf.FolioAssigner;
import cl.timbre.calc.DteTotals;
import cl.timbre.calc.TotalsCalculator;
import cl.timbre.cert.CertificateProvider;
import cl.timbre.cert.SigningMaterial;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.dto.IssueDocumentRequest;
import cl.timbre.dto.ReferenciaRequest;
import cl.timbre.exception.ApiException;
import cl.timbre.model.DteDocument;
import cl.timbre.model.DteReceptor;
import cl.timbre.model.DteReference;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.xml.DteXmlBuilder;
import cl.timbre.xml.EnvioDteBuilder;
import cl.timbre.xml.TedBuilder;
import cl.timbre.xml.XmlSigner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class IssuanceService {

    private final DocumentRepository documentRepository;
    private final FolioAssigner folioAssigner;
    private final CertificateProvider certificateProvider;

    public IssuanceService(DocumentRepository documentRepository, FolioAssigner folioAssigner,
                           CertificateProvider certificateProvider) {
        this.documentRepository = documentRepository;
        this.folioAssigner = folioAssigner;
        this.certificateProvider = certificateProvider;
    }

    public Document issue(Emisor emisor, IssueDocumentRequest request) {
        var existente = documentRepository.findByEmisorIdAndExternalId(emisor.getId(), request.externalId());
        if (existente.isPresent()) {
            return existente.get();
        }

        DteTotals totales;
        try {
            totales = TotalsCalculator.compute(request.lineas());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "lineas_invalidas", e.getMessage());
        }

        FolioAssigner.AssignedFolio asignado = folioAssigner.assign(emisor.getId(), request.tipoDte());
        LocalDateTime timestamp = LocalDateTime.now();

        byte[] sobre = construirSobreFirmado(emisor, request, totales, asignado.folio(),
                asignado.range().getCafXml(), asignado.range().getPrivateKeyPem(), timestamp);

        Document document = Document.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .externalId(request.externalId())
                .tipoDte(request.tipoDte())
                .folio(asignado.folio())
                .rutReceptor(request.receptor().rut())
                .razonSocialReceptor(request.receptor().razonSocial())
                .montoNeto(totales.montoNeto())
                .montoIva(totales.iva())
                .montoTotal(totales.montoTotal())
                .estado(DocumentStatus.PENDIENTE_ENVIO)
                .xmlContent(new String(sobre, StandardCharsets.ISO_8859_1))
                .build();

        return documentRepository.save(document);
    }

    private byte[] construirSobreFirmado(Emisor emisor, IssueDocumentRequest request, DteTotals totales,
                                         int folio, String cafElementXml, String cafPrivateKeyPem,
                                         LocalDateTime timestamp) {
        List<DteReference> referencias = new ArrayList<>();
        int numero = 1;
        for (ReferenciaRequest r : request.referencias()) {
            referencias.add(new DteReference(numero++, r.tipoDocRef(), r.folioRef(),
                    r.fechaRef(), r.codigoRef(), r.razonRef()));
        }

        DteDocument dte = new DteDocument(emisor,
                new DteReceptor(request.receptor().rut(), request.receptor().razonSocial(),
                        request.receptor().giro(), request.receptor().direccion(), request.receptor().comuna()),
                request.tipoDte(), folio, request.fechaEmision(), totales, referencias);

        org.w3c.dom.Document doc = DteXmlBuilder.build(dte);
        Element documento = (Element) doc.getElementsByTagNameNS(DteXmlBuilder.NS, "Documento").item(0);

        PrivateKey cafKey = CafParser.privateKey(cafPrivateKeyPem);
        Element ted = TedBuilder.build(doc, dte, cafElementXml, cafKey, timestamp);
        documento.appendChild(ted);

        Element tmstFirma = doc.createElementNS(DteXmlBuilder.NS, "TmstFirma");
        tmstFirma.setTextContent(timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        documento.appendChild(tmstFirma);

        SigningMaterial material = certificateProvider.forEmisor(emisor);
        XmlSigner.sign(doc, doc.getDocumentElement(),
                DteXmlBuilder.documentId(request.tipoDte(), folio), material);

        return EnvioDteBuilder.build(emisor, List.of(doc), material, timestamp);
    }
}
