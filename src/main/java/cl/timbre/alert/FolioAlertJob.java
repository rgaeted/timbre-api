package cl.timbre.alert;

import cl.timbre.caf.FolioAssigner;
import cl.timbre.domain.Emisor;
import cl.timbre.domain.FolioAlert;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.repository.FolioAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class FolioAlertJob {

    private static final Logger log = LoggerFactory.getLogger(FolioAlertJob.class);
    private static final int[] TIPOS_SOPORTADOS = {33, 61};
    private static final long ALERTA_DELAY_MS = 24 * 60 * 60 * 1000;  // 24 hours

    private final EmisorRepository emisorRepository;
    private final FolioAssigner folioAssigner;
    private final FolioAlertRepository folioAlertRepository;
    private final FolioAlertService folioAlertService;

    @Value("${timbre.folio-alert-threshold:20}")
    private int threshold = 20;

    public FolioAlertJob(EmisorRepository emisorRepository, FolioAssigner folioAssigner,
                        FolioAlertRepository folioAlertRepository, FolioAlertService folioAlertService) {
        this.emisorRepository = emisorRepository;
        this.folioAssigner = folioAssigner;
        this.folioAlertRepository = folioAlertRepository;
        this.folioAlertService = folioAlertService;
    }

    public void verificarYAlertar() {
        List<Emisor> emisores = emisorRepository.findAll();
        int alertasEnviadas = 0;

        for (Emisor emisor : emisores) {
            try {
                Map<Integer, Integer> foliosPorTipo = new HashMap<>();
                List<Integer> tiposConAlerta = new java.util.ArrayList<>();

                for (int tipoDte : TIPOS_SOPORTADOS) {
                    try {
                        int disponibles = folioAssigner.disponibles(emisor.getId(), tipoDte);
                        foliosPorTipo.put(tipoDte, disponibles);

                        if (disponibles < threshold) {
                            tiposConAlerta.add(tipoDte);
                        }
                    } catch (Exception e) {
                        log.error("Error checking folios for emisor {} tipo {}: {}", emisor.getId(), tipoDte, e.getMessage());
                    }
                }

                // Each tipoDte below threshold gets its own independent dedup check and
                // send, since the UNIQUE(emisor_id, tipo_dte) constraint and dedup window
                // are per-(emisor, tipoDte), not per-emisor.
                for (Integer tipoDte : tiposConAlerta) {
                    Optional<FolioAlert> lastAlert = folioAlertRepository.findByEmisorIdAndTipoDte(
                            emisor.getId(), tipoDte);

                    boolean shouldSendAlert = lastAlert.isEmpty() ||
                            lastAlert.get().getLastAlertSentAt().toEpochMilli() < System.currentTimeMillis() - ALERTA_DELAY_MS;

                    if (shouldSendAlert) {
                        boolean sent = folioAlertService.enviarAlerta(emisor, tipoDte, foliosPorTipo);

                        if (sent) {
                            FolioAlert alert = lastAlert.orElseGet(() -> FolioAlert.builder()
                                    .id(java.util.UUID.randomUUID().toString())
                                    .emisorId(emisor.getId())
                                    .tipoDte(tipoDte)
                                    .build());
                            alert.setLastAlertSentAt(Instant.now());
                            alert.setUpdatedAt(Instant.now());
                            folioAlertRepository.save(alert);
                            alertasEnviadas++;
                        } else {
                            log.warn("Folio alert not sent for emisor {} tipo {}; dedup record left unchanged for retry",
                                    emisor.getId(), tipoDte);
                        }
                    } else {
                        log.debug("Alert already sent for emisor {} tipo {} within 24h, skipping",
                                emisor.getId(), tipoDte);
                    }
                }
            } catch (Exception e) {
                log.error("Error processing folio alerts for emisor {}: {}", emisor.getId(), e.getMessage(), e);
            }
        }

        log.info("Folio alert job completed. Checked {} emisores, sent {} alerts", emisores.size(), alertasEnviadas);
    }
}
