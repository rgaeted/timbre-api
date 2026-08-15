package cl.timbre.storage;

import cl.timbre.config.StorageSyncJobProperties;
import cl.timbre.domain.Document;
import cl.timbre.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
public class StorageSyncJob {
    private final StorageService storageService;
    private final DocumentRepository documentRepository;
    private final StorageSyncJobProperties props;

    public StorageSyncJob(StorageService storageService, DocumentRepository documentRepository, StorageSyncJobProperties props) {
        this.storageService = storageService;
        this.documentRepository = documentRepository;
        this.props = props;
    }

    public void runSync() {
        if (!props.isEnabled()) {
            log.debug("StorageSyncJob is disabled");
            return;
        }

        log.info("Starting StorageSyncJob");

        List<Document> fallbackDocs = documentRepository.findByStoredFallbackTrue(
                PageRequest.of(0, props.getBatchSize())
        ).getContent();

        if (fallbackDocs.isEmpty()) {
            log.debug("No documents with stored_fallback=true found");
            return;
        }

        int successCount = 0;
        for (Document doc : fallbackDocs) {
            try {
                if (doc.getXmlContent() != null && doc.getXmlKey() == null) {
                    String xmlKey = doc.getEmisorId() + "/documents/" + doc.getId() + ".xml";
                    storageService.put(xmlKey, doc.getXmlContent().getBytes(StandardCharsets.UTF_8));
                    doc.setXmlKey(xmlKey);
                    doc.setXmlContent(null);
                    log.debug("Migrated XML to storage for document {}", doc.getId());
                }

                if (doc.getPdfContent() != null && doc.getPdfKey() == null) {
                    String pdfKey = doc.getEmisorId() + "/documents/" + doc.getId() + ".pdf";
                    storageService.put(pdfKey, doc.getPdfContent());
                    doc.setPdfKey(pdfKey);
                    doc.setPdfContent(null);
                    log.debug("Migrated PDF to storage for document {}", doc.getId());
                }

                if (doc.getXmlKey() != null && doc.getPdfKey() != null) {
                    doc.setStoredFallback(false);
                    documentRepository.save(doc);
                    successCount++;
                    log.debug("Successfully migrated document {} to storage", doc.getId());
                }
            } catch (StorageException e) {
                log.error("Migration failed for document {}", doc.getId(), e);
            }
        }

        log.info("StorageSyncJob completed: migrated {} out of {} documents", successCount, fallbackDocs.size());
    }
}
