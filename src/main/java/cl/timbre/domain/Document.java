package cl.timbre.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "document")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Document {
    @Id
    private String id;

    @Column(name = "emisor_id", nullable = false)
    private String emisorId;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "tipo_dte", nullable = false)
    private Integer tipoDte;

    @Column(nullable = false)
    private Integer folio;

    @Column(name = "rut_receptor", nullable = false)
    private String rutReceptor;

    @Column(name = "razon_social_receptor", nullable = false)
    private String razonSocialReceptor;

    @Column(name = "monto_neto", nullable = false)
    private Integer montoNeto;

    @Column(name = "monto_iva", nullable = false)
    private Integer montoIva;

    @Column(name = "monto_total", nullable = false)
    private Integer montoTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus estado;

    @Column(name = "track_id")
    private String trackId;

    @Column(name = "xml_key")
    private String xmlKey;

    @Column(name = "xml_content")
    private String xmlContent;

    @Column(name = "pdf_key")
    private String pdfKey;

    @Column(name = "sii_estado_detalle")
    private String siiEstadoDetalle;

    @Column(name = "documento_referenciado_id")
    private String documentoReferenciadoId;

    @Column(name = "intentos_consulta", nullable = false)
    @Builder.Default
    private Integer intentosConsulta = 0;

    @Column(name = "proxima_consulta_at")
    private Instant proximaConsultaAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
