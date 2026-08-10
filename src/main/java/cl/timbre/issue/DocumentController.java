package cl.timbre.issue;

import cl.timbre.auth.EmisorContext;
import cl.timbre.dto.DocumentResponse;
import cl.timbre.dto.IssueDocumentRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final IssuanceService issuanceService;

    public DocumentController(IssuanceService issuanceService) {
        this.issuanceService = issuanceService;
    }

    @PostMapping
    public DocumentResponse emitir(@Valid @RequestBody IssueDocumentRequest request) {
        return DocumentResponse.from(issuanceService.issue(EmisorContext.current(), request));
    }
}
