package cl.timbre.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record ReferenciaRequest(
        int tipoDocRef,
        int folioRef,
        @NotNull LocalDate fechaRef,
        int codigoRef,
        @NotBlank String razonRef
) {}
