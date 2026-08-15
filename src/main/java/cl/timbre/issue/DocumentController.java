package cl.timbre.issue;

import cl.timbre.auth.EmisorContext;
import cl.timbre.domain.Document;
import cl.timbre.domain.Emisor;
import cl.timbre.dto.DocumentResponse;
import cl.timbre.dto.IssueDocumentRequest;
import cl.timbre.exception.ApiException;
import cl.timbre.repository.DocumentRepository;
import cl.timbre.storage.StorageException;
import cl.timbre.storage.StorageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final IssuanceService issuanceService;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;

    public DocumentController(IssuanceService issuanceService, DocumentRepository documentRepository, StorageService storageService) {
        this.issuanceService = issuanceService;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
    }

    @PostMapping
    public DocumentResponse emitir(@Valid @RequestBody IssueDocumentRequest request) {
        return DocumentResponse.from(issuanceService.issue(EmisorContext.current(), request));
    }

    @GetMapping("/{id}/xml")
    public ResponseEntity<byte[]> getXml(@PathVariable String id) {
        Emisor emisor = EmisorContext.current();
        Document document = documentRepository.findByIdAndEmisorId(id, emisor.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "documento_no_encontrado",
                        "Documento no encontrado"));

        byte[] xmlData = null;
        if (document.getXmlKey() != null) {
            try {
                xmlData = storageService.getBytes(document.getXmlKey());
            } catch (StorageException e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Storage unavailable".getBytes(StandardCharsets.UTF_8));
            }
        }

        if (xmlData == null && document.getXmlContent() != null) {
            xmlData = document.getXmlContent().getBytes(StandardCharsets.UTF_8);
        }

        if (xmlData == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header("Content-Type", "application/xml")
                .body(xmlData);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> getPdf(@PathVariable String id) {
        Emisor emisor = EmisorContext.current();
        Document document = documentRepository.findByIdAndEmisorId(id, emisor.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "documento_no_encontrado",
                        "Documento no encontrado"));

        byte[] pdfData = null;
        if (document.getPdfKey() != null) {
            try {
                pdfData = storageService.getBytes(document.getPdfKey());
            } catch (StorageException e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Storage unavailable".getBytes(StandardCharsets.UTF_8));
            }
        }

        if (pdfData == null && document.getPdfContent() != null) {
            pdfData = document.getPdfContent();
        }

        if (pdfData == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .body(pdfData);
    }
}
