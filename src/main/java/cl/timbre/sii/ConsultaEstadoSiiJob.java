package cl.timbre.sii;

import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import cl.timbre.domain.Emisor;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.repository.EmisorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Consulta al SII el estado de los documentos ENVIADO por su trackId, y los mueve a
 * ACEPTADO/RECHAZADO. Ante cualquier respuesta ambigua, no reconocida, o error de
 * comunicacion, reintenta con backoff propio en vez de asumir un estado -- nunca se
 * marca ACEPTADO ni RECHAZADO ante la duda.
 */
@Component
public class ConsultaEstadoSiiJob {

    private static final Logger log = LoggerFactory.getLogger(ConsultaEstadoSiiJob.class);
    private static final List<DocumentStatus> ESTADOS_CANDIDATOS = List.of(DocumentStatus.ENVIADO);
    private static final int TAMANO_LOTE = 200;

    private final DocumentRepository documentRepository;
    private final EmisorRepository emisorRepository;
    private final SiiAuthClient authClient;
    private final SiiConsultaClient consultaClient;
    private final SiiProperties properties;

    public ConsultaEstadoSiiJob(DocumentRepository documentRepository, EmisorRepository emisorRepository,
                                SiiAuthClient authClient, SiiConsultaClient consultaClient, SiiProperties properties) {
        this.documentRepository = documentRepository;
        this.emisorRepository = emisorRepository;
        this.authClient = authClient;
        this.consultaClient = consultaClient;
        this.properties = properties;
    }

    public void consultarEnviados() {
        List<Document> candidatos = documentRepository.findByEstadoInAndProximaConsultaAtBefore(
                ESTADOS_CANDIDATOS, Instant.now(), PageRequest.of(0, TAMANO_LOTE));

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
     * Misma razon que en EnvioSiiJob: una falla de autenticacion es del emisor/SII,
     * no de un documento en particular. No se le carga el intento a nadie.
     */
    private void reprogramarSinCargarIntento(List<Document> documentos, Exception e) {
        Instant proximoIntento = Instant.now().plusMillis(properties.consultaBackoffMs());
        String detalle = "No se pudo autenticar con el SII: " + mensajeTruncado(e);
        for (Document documento : documentos) {
            documento.setSiiEstadoDetalle(detalle);
            documento.setProximaConsultaAt(proximoIntento);
            documentRepository.save(documento);
        }
    }

    private void procesarDocumento(Emisor emisor, SiiAuthClient.Token token, Document documento) {
        try {
            SiiConsultaClient.ResultadoConsulta resultado =
                    consultaClient.consultar(emisor, token, documento.getTrackId());

            switch (resultado.estado()) {
                case ACEPTADO -> finalizar(documento, DocumentStatus.ACEPTADO, resultado.detalle());
                case RECHAZADO -> finalizar(documento, DocumentStatus.RECHAZADO, resultado.detalle());
                case EN_PROCESO -> registrarReintento(documento, resultado.detalle());
            }
        } catch (Exception e) {
            log.error("Fallo la consulta de estado del documento {} folio {}",
                    documento.getId(), documento.getFolio(), e);
            registrarReintento(documento, mensajeTruncado(e));
        }
    }

    private void finalizar(Document documento, DocumentStatus estado, String detalle) {
        log.info("Documento {} folio {} quedo {} en el SII", documento.getId(), documento.getFolio(), estado);
        documento.setEstado(estado);
        documento.setSiiEstadoDetalle(detalle);
        documento.setProximaConsultaAt(null);
        documentRepository.save(documento);
    }

    private void registrarReintento(Document documento, String detalle) {
        int intentos = documento.getIntentosConsulta() + 1;
        documento.setIntentosConsulta(intentos);
        documento.setSiiEstadoDetalle(detalle);
        documento.setProximaConsultaAt(
                intentos < properties.consultaMaxIntentos()
                        ? Instant.now().plusMillis(properties.consultaBackoffMs())
                        : null);
        documentRepository.save(documento);
    }

    private String mensajeTruncado(Exception e) {
        String mensaje = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return mensaje.length() <= 500 ? mensaje : mensaje.substring(0, 500);
    }
}
