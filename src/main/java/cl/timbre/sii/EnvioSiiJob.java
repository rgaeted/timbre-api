package cl.timbre.sii;

import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.xml.DteXmlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    private final DocumentRepository documentRepository;
    private final EmisorRepository emisorRepository;
    private final SiiAuthClient authClient;
    private final SiiUploadClient uploadClient;
    private final SiiProperties properties;

    public EnvioSiiJob(DocumentRepository documentRepository, EmisorRepository emisorRepository,
                       SiiAuthClient authClient, SiiUploadClient uploadClient, SiiProperties properties) {
        this.documentRepository = documentRepository;
        this.emisorRepository = emisorRepository;
        this.authClient = authClient;
        this.uploadClient = uploadClient;
        this.properties = properties;
    }

    public void enviarPendientes() {
        List<Document> candidatos = documentRepository
                .findByEstadoInAndProximaConsultaAtBefore(ESTADOS_CANDIDATOS, Instant.now())
                .stream()
                .filter(d -> d.getEstado() == DocumentStatus.PENDIENTE_ENVIO || d.getXmlContent() != null)
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
            documentos.forEach(documento -> registrarFalla(documento, e));
            return;
        }

        for (Document documento : documentos) {
            procesarDocumento(emisor, token, documento);
        }
    }

    private void procesarDocumento(Emisor emisor, SiiAuthClient.Token token, Document documento) {
        try {
            String nombreArchivo = DteXmlBuilder.documentId(documento.getTipoDte(), documento.getFolio()) + ".xml";
            SiiUploadClient.ResultadoSubida resultado = uploadClient.subir(
                    emisor, token, documento.getXmlContent(), nombreArchivo);

            if (resultado.exitoso()) {
                documento.setEstado(DocumentStatus.ENVIADO);
                documento.setTrackId(resultado.trackId());
                documento.setIntentosConsulta(0);
                documento.setSiiEstadoDetalle(null);
                documento.setProximaConsultaAt(Instant.now().plusMillis(properties.envioConsultaDelayMs()));
                documentRepository.save(documento);
            } else {
                registrarRechazoDefinitivo(documento, resultado.detalle());
            }
        } catch (Exception e) {
            log.error("Fallo el envio del documento {} folio {}", documento.getId(), documento.getFolio(), e);
            registrarFalla(documento, e);
        }
    }

    private void registrarFalla(Document documento, Exception e) {
        int intentos = documento.getIntentosConsulta() + 1;
        documento.setEstado(DocumentStatus.ERROR_ENVIO);
        documento.setIntentosConsulta(intentos);
        documento.setSiiEstadoDetalle(mensajeTruncado(e));
        documento.setProximaConsultaAt(
                intentos < properties.envioMaxIntentos()
                        ? Instant.now().plusMillis(properties.envioBackoffMs())
                        : null);
        documentRepository.save(documento);
    }

    private void registrarRechazoDefinitivo(Document documento, String detalle) {
        documento.setEstado(DocumentStatus.ERROR_ENVIO);
        documento.setIntentosConsulta(properties.envioMaxIntentos());
        documento.setProximaConsultaAt(null);
        documento.setSiiEstadoDetalle(detalle);
        documentRepository.save(documento);
    }

    private String mensajeTruncado(Exception e) {
        String mensaje = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return mensaje.length() <= 500 ? mensaje : mensaje.substring(0, 500);
    }
}
