package cl.timbre.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record IssueLine(
        @NotBlank String descripcion,
        @Min(1) int cantidad,
        int precioUnitarioBruto,
        LineType tipo
) {
    public IssueLine {
        if (tipo == null) {
            tipo = LineType.AFECTO;
        }
    }
}
