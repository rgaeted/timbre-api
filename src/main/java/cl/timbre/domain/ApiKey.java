package cl.timbre.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "api_key")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApiKey {
    @Id
    private String id;

    @Column(name = "emisor_id", nullable = false)
    private String emisorId;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String prefijo;

    @Column(nullable = false, unique = true)
    private String hash;

    @Column(name = "revocada_at")
    private Instant revocadaAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
