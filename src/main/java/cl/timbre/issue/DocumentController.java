package cl.timbre.issue;

import cl.timbre.auth.EmisorContext;
import cl.timbre.domain.Document;
import cl.timbre.domain.Emisor;
import cl.timbre.dto.DocumentResponse;
import cl.timbre.dto.IssueDocumentRequest;
import cl.timbre.exception.ApiException;
import cl.timbre.repository.DocumentRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final IssuanceService issuanceService;
    private final DocumentRepository documentRepository;

    public DocumentController(IssuanceService issuanceService, DocumentRepository documentRepository) {
        this.issuanceService = issuanceService;
        this.documentRepository = documentRepository;
    }

    @PostMapping
    public DocumentResponse emitir(@Valid @RequestBody IssueDocumentRequest request) {
        return DocumentResponse.from(issuanceService.issue(EmisorContext.current(), request));
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<?> pdf(@PathVariable String id) {
        Emisor emisor = EmisorContext.current();
        Document doc = documentRepository.findByIdAndEmisorId(id, emisor.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "documento_no_encontrado",
                        "Documento no encontrado"));

        if (doc.getPdfContent() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "pdf_no_disponible",
                    "El PDF no está disponible. La generación falló durante la emisión.");
        }

        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(doc.getPdfContent());
    }
}
