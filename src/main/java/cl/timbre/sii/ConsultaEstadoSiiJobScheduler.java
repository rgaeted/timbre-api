package cl.timbre.sii;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara ConsultaEstadoSiiJob.consultarEnviados() periodicamente. Separado de
 * ConsultaEstadoSiiJob por la misma razon que EnvioSiiJobScheduler: poder
 * desactivar el disparo automatico en tests sin perder la capacidad de invocar el
 * job directamente.
 */
@Component
@ConditionalOnProperty(prefix = "sii", name = "consulta-job-enabled", havingValue = "true", matchIfMissing = true)
public class ConsultaEstadoSiiJobScheduler {

    private final ConsultaEstadoSiiJob job;

    public ConsultaEstadoSiiJobScheduler(ConsultaEstadoSiiJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${sii.consulta-job-fixed-delay-ms}")
    public void ejecutar() {
        job.consultarEnviados();
    }
}
