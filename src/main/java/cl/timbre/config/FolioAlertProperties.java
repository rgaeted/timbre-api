package cl.timbre.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "timbre.folio-alert")
public class FolioAlertProperties {
    private boolean enabled = true;

    // Bound for @ConfigurationProperties metadata/documentation purposes only; not
    // read directly in code. @Scheduled requires a compile-time-resolvable annotation
    // value, so FolioAlertJobScheduler uses the raw ${timbre.folio-alert.cron:...}
    // placeholder instead of properties.getCron(). Kept here (rather than removed) so
    // Spring's relaxed @ConfigurationProperties binding still recognizes the
    // timbre.folio-alert.cron key instead of failing/ignoring it.
    private String cron = "0 0 6 * * *";  // Daily at 6 AM UTC (Spring cron: seconds minutes hours day month weekday)
}
