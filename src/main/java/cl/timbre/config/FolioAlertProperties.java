package cl.timbre.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "timbre.folio-alert")
public class FolioAlertProperties {
    private boolean enabled = true;
    private String cron = "0 6 * * *";  // Daily at 6 AM UTC
}
