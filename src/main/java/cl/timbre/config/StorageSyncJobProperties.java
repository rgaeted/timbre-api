package cl.timbre.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "storage.sync")
public class StorageSyncJobProperties {
    private boolean enabled = true;
    private long fixedDelayMs = 300000;
    private int batchSize = 100;
}
