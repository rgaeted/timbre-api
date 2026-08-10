package cl.timbre.dto;

import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record DocumentResponse(
        String id,
        String externalId,
        int tipoDte,
        int folio,
        DocumentStatus estado,
        int montoNeto,
        int montoIva,
        int montoTotal,
        String xmlBase64
) {
    public static DocumentResponse from(Document document) {
        String xmlBase64 = document.getXmlContent() == null
                ? null
                : Base64.getEncoder().encodeToString(
                        document.getXmlContent().getBytes(StandardCharsets.ISO_8859_1));

        return new DocumentResponse(document.getId(), document.getExternalId(), document.getTipoDte(),
                document.getFolio(), document.getEstado(), document.getMontoNeto(), document.getMontoIva(),
                document.getMontoTotal(), xmlBase64);
    }
}
