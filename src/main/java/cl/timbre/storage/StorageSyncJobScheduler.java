package cl.timbre.storage;

import cl.timbre.config.StorageSyncJobProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableConfigurationProperties(StorageSyncJobProperties.class)
public class StorageSyncJobScheduler {
    private final StorageSyncJob storageSyncJob;

    public StorageSyncJobScheduler(StorageSyncJob storageSyncJob) {
        this.storageSyncJob = storageSyncJob;
    }

    @Scheduled(fixedDelayString = "${storage.sync.fixed-delay-ms:300000}")
    public void scheduleSync() {
        try {
            storageSyncJob.runSync();
        } catch (Exception e) {
            log.error("StorageSyncJobScheduler failed", e);
        }
    }
}
