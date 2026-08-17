package cl.timbre.alert;

import cl.timbre.config.FolioAlertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FolioAlertJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(FolioAlertJobScheduler.class);

    private final FolioAlertJob folioAlertJob;
    private final FolioAlertProperties properties;

    public FolioAlertJobScheduler(FolioAlertJob folioAlertJob, FolioAlertProperties properties) {
        this.folioAlertJob = folioAlertJob;
        this.properties = properties;
    }

    @Scheduled(cron = "${timbre.folio-alert.cron:0 0 6 * * *}")
    public void runAlert() {
        if (!properties.isEnabled()) {
            log.debug("Folio alert job disabled, skipping");
            return;
        }

        log.info("Starting folio alert job");
        try {
            folioAlertJob.verificarYAlertar();
        } catch (Exception e) {
            log.error("Folio alert job failed: {}", e.getMessage(), e);
        }
    }
}
