package cl.timbre.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "folio_range")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FolioRange {
    @Id
    private String id;

    @Column(name = "emisor_id", nullable = false)
    private String emisorId;

    @Column(name = "tipo_dte", nullable = false)
    private Integer tipoDte;

    @Column(name = "folio_desde", nullable = false)
    private Integer folioDesde;

    @Column(name = "folio_hasta", nullable = false)
    private Integer folioHasta;

    @Column(name = "folio_actual", nullable = false)
    private Integer folioActual;

    @Column(name = "caf_xml", nullable = false)
    private String cafXml;

    @Column(name = "private_key_pem", nullable = false)
    private String privateKeyPem;

    @Column(name = "fecha_autorizacion", nullable = false)
    private LocalDate fechaAutorizacion;

    @Column(nullable = false)
    @Builder.Default
    private Boolean agotado = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
