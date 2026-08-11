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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class IssuanceService {

    private static final Logger log = LoggerFactory.getLogger(IssuanceService.class);

    private static final Set<Integer> TIPOS_SOPORTADOS = Set.of(33, 61);

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
            Document previo = existente.get();
            if (previo.getEstado() == DocumentStatus.ERROR_ENVIO) {
                throw new ApiException(HttpStatus.CONFLICT, "emision_previa_fallida",
                        "El documento " + request.externalId() + " quedo en ERROR_ENVIO con el folio "
                                + previo.getFolio() + " consumido. Reintenta con otro externalId.");
            }
            return previo;
        }
        if (!TIPOS_SOPORTADOS.contains(request.tipoDte())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "tipo_dte_no_soportado",
                    "Tipo de documento no soportado: " + request.tipoDte());
        }
        if (request.tipoDte() == 61 && request.referencias().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "referencia_requerida",
                    "Una nota de credito debe referenciar el documento que corrige");
        }

        DteTotals totales;
        try {
            totales = TotalsCalculator.compute(request.lineas());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "lineas_invalidas", e.getMessage());
        }

        FolioAssigner.AssignedFolio asignado = folioAssigner.assign(emisor.getId(), request.tipoDte());
        LocalDateTime timestamp = LocalDateTime.now();

        Document.DocumentBuilder documento = Document.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .externalId(request.externalId())
                .tipoDte(request.tipoDte())
                .folio(asignado.folio())
                .rutReceptor(request.receptor().rut())
                .razonSocialReceptor(request.receptor().razonSocial())
                .montoNeto(totales.montoNeto())
                .montoIva(totales.iva())
                .montoTotal(totales.montoTotal());

        try {
            byte[] sobre = construirSobreFirmado(emisor, request, totales, asignado.folio(),
                    asignado.range().getCafXml(), asignado.range().getPrivateKeyPem(), timestamp);
            return guardar(documento
                    .estado(DocumentStatus.PENDIENTE_ENVIO)
                    .xmlContent(new String(sobre, StandardCharsets.ISO_8859_1))
                    .proximaConsultaAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.error("No se pudo emitir el documento externalId={} folio={} tipoDte={}",
                    request.externalId(), asignado.folio(), request.tipoDte(), e);
            try {
                guardar(documento
                        .estado(DocumentStatus.ERROR_ENVIO)
                        .xmlContent(null)
                        .siiEstadoDetalle(mensajeTruncado(e))
                        .build());
            } catch (RuntimeException persistencia) {
                e.addSuppressed(persistencia);
            }
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error_emision",
                    "No se pudo emitir el documento");
        }
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

    /**
     * Si dos requests con el mismo externalId llegan a la vez, ambas pasan el chequeo de
     * idempotencia de mas arriba y cada una alcanza a consumir su propio folio antes de
     * intentar guardar. La segunda en llegar aca choca con el UNIQUE(emisor_id, external_id)
     * y se resuelve devolviendo el documento de la que gano la carrera; el folio de la que
     * perdio queda consumido sin documento asociado, igual que cualquier otro folio quemado
     * por un error de emision — es una realidad operacional normal en DTE, no un bug.
     */
    private Document guardar(Document document) {
        try {
            return documentRepository.save(document);
        } catch (DataIntegrityViolationException e) {
            return documentRepository
                    .findByEmisorIdAndExternalId(document.getEmisorId(), document.getExternalId())
                    .orElseThrow(() -> e);
        }
    }

    private String mensajeTruncado(Exception e) {
        String mensaje = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return mensaje.length() <= 500 ? mensaje : mensaje.substring(0, 500);
    }
}
