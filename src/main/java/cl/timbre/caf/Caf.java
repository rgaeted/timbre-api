package cl.timbre.caf;

import java.time.LocalDate;

public record Caf(
        String rutEmisor,
        String razonSocial,
        int tipoDte,
        int desde,
        int hasta,
        LocalDate fechaAutorizacion,
        /** El elemento CAF serializado tal cual viene, para embeberlo en el TED. */
        String cafElementXml,
        /** Llave privada en PEM PKCS#1, tal como la entrega el SII en RSASK. */
        String privateKeyPem
) {}
