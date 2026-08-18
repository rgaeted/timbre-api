package cl.timbre.sii;

import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.storage.StorageService;
import cl.timbre.xml.DteXmlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Envia al SII los documentos PENDIENTE_ENVIO (nunca intentados) o ERROR_ENVIO con
 * XML ya firmado (fallo un envio anterior, no hace falta re-emitir). Los ERROR_ENVIO
 * sin XML son de la fase de emision (armado/firma fallo antes de completarse) y no
 * les toca a este job -- IssuanceService ya los bloquea para reintento de emision.
 */
@Component
public class EnvioSiiJob {

    private static final Logger log = LoggerFactory.getLogger(EnvioSiiJob.class);
    private static final List<DocumentStatus> ESTADOS_CANDIDATOS =
            List.of(DocumentStatus.PENDIENTE_ENVIO, DocumentStatus.ERROR_ENVIO);
    private static final int TAMANO_LOTE = 200;

    private final DocumentRepository documentRepository;
    private final EmisorRepository emisorRepository;
    private final SiiAuthClient authClient;
    private final SiiUploadClient uploadClient;
    private final SiiProperties properties;
    private final StorageService storageService;

    public EnvioSiiJob(DocumentRepository documentRepository, EmisorRepository emisorRepository,
                       SiiAuthClient authClient, SiiUploadClient uploadClient, SiiProperties properties,
                       StorageService storageService) {
        this.documentRepository = documentRepository;
        this.emisorRepository = emisorRepository;
        this.authClient = authClient;
        this.uploadClient = uploadClient;
        this.properties = properties;
        this.storageService = storageService;
    }

    public void enviarPendientes() {
        List<Document> candidatos = documentRepository
                .findByEstadoInAndProximaConsultaAtBefore(
                        ESTADOS_CANDIDATOS, Instant.now(), PageRequest.of(0, TAMANO_LOTE))
                .stream()
                .filter(d -> d.getEstado() == DocumentStatus.PENDIENTE_ENVIO
                        || d.getXmlContent() != null || d.getXmlKey() != null)
                .toList();

        Map<String, List<Document>> porEmisor = candidatos.stream()
                .collect(Collectors.groupingBy(Document::getEmisorId));

        porEmisor.forEach(this::procesarEmisor);
    }

    private void procesarEmisor(String emisorId, List<Document> documentos) {
        Emisor emisor = emisorRepository.findById(emisorId).orElseThrow();

        SiiAuthClient.Token token;
        try {
            token = authClient.obtenerToken(emisor);
        } catch (Exception e) {
            log.error("No se pudo autenticar con el SII para el emisor {}", emisorId, e);
            reprogramarSinCargarIntento(documentos, e);
            return;
        }

        for (Document documento : documentos) {
            procesarDocumento(emisor, token, documento);
        }
    }

    /**
     * Una falla de autenticacion es un problema del emisor/SII, no de un documento
     * en particular -- ningun documento de este lote llego siquiera a intentarse
     * subir. No se le carga el intento a nadie, solo se reprograma para la proxima
     * corrida (con el mismo backoff), para no agotarle el presupuesto de reintentos
     * a documentos que no tuvieron ninguna oportunidad real de enviarse.
     */
    private void reprogramarSinCargarIntento(List<Document> documentos, Exception e) {
        Instant proximoIntento = Instant.now().plusMillis(properties.envioBackoffMs());
        String detalle = "No se pudo autenticar con el SII: " + mensajeTruncado(e);
        for (Document documento : documentos) {
            documento.setSiiEstadoDetalle(detalle);
            documento.setProximaConsultaAt(proximoIntento);
            documentRepository.save(documento);
        }
    }

    private void procesarDocumento(Emisor emisor, SiiAuthClient.Token token, Document documento) {
        try {
            String xmlContent = resolverXmlContent(documento);
            String nombreArchivo = DteXmlBuilder.documentId(documento.getTipoDte(), documento.getFolio()) + ".xml";
            SiiUploadClient.ResultadoSubida resultado = uploadClient.subir(
                    emisor, token, xmlContent, nombreArchivo);

            if (resultado.exitoso()) {
                log.info("Documento {} folio {} enviado al SII, trackId={}",
                        documento.getId(), documento.getFolio(), resultado.trackId());
                documento.setEstado(DocumentStatus.ENVIADO);
                documento.setTrackId(resultado.trackId());
                documento.setIntentosConsulta(0);
                documento.setSiiEstadoDetalle(null);
                documento.setProximaConsultaAt(Instant.now().plusMillis(properties.envioConsultaDelayMs()));
                documentRepository.save(documento);
            } else {
                // No hay WSDL del SII: no se conoce que codigos de STATUS son
                // transitorios y cuales definitivos. Ante la duda, se trata como
                // transitorio (se reintenta con backoff hasta el maximo) -- es el
                // default mas seguro dado que no se puede distinguir todavia.
                log.warn("El SII rechazo la subida del documento {} folio {}: {}",
                        documento.getId(), documento.getFolio(), resultado.detalle());
                registrarFalla(documento, resultado.detalle());
            }
        } catch (Exception e) {
            log.error("Fallo el envio del documento {} folio {}", documento.getId(), documento.getFolio(), e);
            registrarFalla(documento, mensajeTruncado(e));
        }
    }

    private void registrarFalla(Document documento, String detalle) {
        int intentos = documento.getIntentosConsulta() + 1;
        documento.setEstado(DocumentStatus.ERROR_ENVIO);
        documento.setIntentosConsulta(intentos);
        documento.setSiiEstadoDetalle(detalle);
        documento.setProximaConsultaAt(
                intentos < properties.envioMaxIntentos()
                        ? Instant.now().plusMillis(properties.envioBackoffMs())
                        : null);
        documentRepository.save(documento);
    }

    /**
     * El XML puede estar en la columna BYTEA (fallback) o en storage (S3/R2/local,
     * fase D) -- si hay xmlKey, ese es el dato vigente (xmlContent se limpia al
     * persistir exitosamente en storage). Una StorageException aca se propaga y
     * la captura el catch de procesarDocumento, que ya trata cualquier falla como
     * transitoria y reprograma con backoff.
     */
    private String resolverXmlContent(Document documento) throws cl.timbre.storage.StorageException {
        if (documento.getXmlKey() != null) {
            return new String(storageService.getBytes(documento.getXmlKey()), StandardCharsets.ISO_8859_1);
        }
        return documento.getXmlContent();
    }

    private String mensajeTruncado(Exception e) {
        String mensaje = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return mensaje.length() <= 500 ? mensaje : mensaje.substring(0, 500);
    }
}
