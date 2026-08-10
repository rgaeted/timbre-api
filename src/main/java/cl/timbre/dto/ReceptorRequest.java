package cl.timbre.dto;

import jakarta.validation.constraints.NotBlank;

public record ReceptorRequest(
        @NotBlank String rut,
        @NotBlank String razonSocial,
        @NotBlank String giro,
        @NotBlank String direccion,
        @NotBlank String comuna
) {}
