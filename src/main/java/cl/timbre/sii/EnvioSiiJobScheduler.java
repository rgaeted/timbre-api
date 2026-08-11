package cl.timbre.sii;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara EnvioSiiJob.enviarPendientes() periodicamente. Separado de EnvioSiiJob
 * para poder desactivar el disparo automatico en tests (sii.envio-job-enabled=false)
 * sin dejar de poder invocar el job directamente.
 */
@Component
@ConditionalOnProperty(prefix = "sii", name = "envio-job-enabled", havingValue = "true", matchIfMissing = true)
public class EnvioSiiJobScheduler {

    private final EnvioSiiJob job;

    public EnvioSiiJobScheduler(EnvioSiiJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${sii.envio-job-fixed-delay-ms}")
    public void ejecutar() {
        job.enviarPendientes();
    }
}
