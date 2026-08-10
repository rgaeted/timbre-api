package cl.timbre.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.LocalDate;
import java.util.List;

public record IssueDocumentRequest(
        @NotBlank String externalId,
        int tipoDte,
        @NotNull @PastOrPresent LocalDate fechaEmision,
        @NotNull @Valid ReceptorRequest receptor,
        @NotEmpty @Valid List<IssueLine> lineas,
        @Valid List<ReferenciaRequest> referencias
) {
    public IssueDocumentRequest {
        if (referencias == null) {
            referencias = List.of();
        }
    }
}
