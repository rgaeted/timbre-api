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
            Map<Integer, Integer> foliosPorTipo = new HashMap<>();
            Integer tipoDteConAlerta = null;

            for (int tipoDte : TIPOS_SOPORTADOS) {
                try {
                    int disponibles = folioAssigner.disponibles(emisor.getId(), tipoDte);
                    foliosPorTipo.put(tipoDte, disponibles);

                    if (disponibles < threshold) {
                        if (tipoDteConAlerta == null) {
                            tipoDteConAlerta = tipoDte;  // Remember first tipo that triggered
                        }
                    }
                } catch (Exception e) {
                    log.error("Error checking folios for emisor {} tipo {}: {}", emisor.getId(), tipoDte, e.getMessage());
                }
            }

            if (tipoDteConAlerta != null) {
                final Integer finalTipoDte = tipoDteConAlerta;
                Optional<FolioAlert> lastAlert = folioAlertRepository.findByEmisorIdAndTipoDte(
                        emisor.getId(), finalTipoDte);

                boolean shouldSendAlert = lastAlert.isEmpty() ||
                        lastAlert.get().getLastAlertSentAt().toEpochMilli() < System.currentTimeMillis() - ALERTA_DELAY_MS;

                if (shouldSendAlert) {
                    folioAlertService.enviarAlerta(emisor, finalTipoDte, foliosPorTipo);

                    FolioAlert alert = lastAlert.orElseGet(() -> FolioAlert.builder()
                            .id(java.util.UUID.randomUUID().toString())
                            .emisorId(emisor.getId())
                            .tipoDte(finalTipoDte)
                            .build());
                    alert.setLastAlertSentAt(Instant.now());
                    alert.setUpdatedAt(Instant.now());
                    folioAlertRepository.save(alert);
                    alertasEnviadas++;
                } else {
                    log.debug("Alert already sent for emisor {} tipo {} within 24h, skipping",
                            emisor.getId(), finalTipoDte);
                }
            }
        }

        log.info("Folio alert job completed. Checked {} emisores, sent {} alerts", emisores.size(), alertasEnviadas);
    }
}
