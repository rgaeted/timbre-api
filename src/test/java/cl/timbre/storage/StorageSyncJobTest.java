package cl.timbre.storage;

import cl.timbre.config.StorageSyncJobProperties;
import cl.timbre.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StorageSyncJobTest {
    @Mock private StorageService storageService;
    @Mock private DocumentRepository documentRepository;

    @Test
    void testSyncJobSkipsWhenDisabled() {
        StorageSyncJobProperties props = new StorageSyncJobProperties();
        props.setEnabled(false);
        StorageSyncJob job = new StorageSyncJob(storageService, documentRepository, props);

        job.runSync();

        verify(documentRepository, never()).findByStoredFallbackTrue(any());
    }

    @Test
    void testSyncJobHandlesEmptyFallbackDocuments() {
        StorageSyncJobProperties props = new StorageSyncJobProperties();
        props.setEnabled(true);
        StorageSyncJob job = new StorageSyncJob(storageService, documentRepository, props);

        // Mock returns empty page
        org.mockito.Mockito.when(documentRepository.findByStoredFallbackTrue(any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        job.runSync();

        verify(documentRepository).findByStoredFallbackTrue(any());
    }
}
