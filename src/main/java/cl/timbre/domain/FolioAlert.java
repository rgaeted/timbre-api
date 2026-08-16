package cl.timbre.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "folio_alert")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FolioAlert {
    @Id
    private String id;  // UUID as string

    @Column(name = "emisor_id", nullable = false)
    private String emisorId;

    @Column(name = "tipo_dte", nullable = false)
    private Integer tipoDte;

    @Column(name = "last_alert_sent_at", nullable = false)
    private Instant lastAlertSentAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
