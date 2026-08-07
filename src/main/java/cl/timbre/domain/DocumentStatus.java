package cl.timbre.domain;

public enum DocumentStatus {
    PENDIENTE_ENVIO,
    ENVIADO,
    ACEPTADO,
    ACEPTADO_CON_REPARO,
    RECHAZADO,
    ERROR_ENVIO,
    ANULADO;

    /** Estados en los que ya no tiene sentido seguir consultando al SII. */
    public boolean esTerminal() {
        return this == ACEPTADO || this == ACEPTADO_CON_REPARO
                || this == RECHAZADO || this == ANULADO;
    }
}
