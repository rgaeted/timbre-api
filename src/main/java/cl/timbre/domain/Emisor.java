package cl.timbre.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "emisor")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Emisor {
    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String rut;

    @Column(name = "razon_social", nullable = false)
    private String razonSocial;

    @Column(nullable = false)
    private String giro;

    @Column(nullable = false)
    private Integer acteco;

    @Column(name = "direccion_origen", nullable = false)
    private String direccionOrigen;

    @Column(name = "comuna_origen", nullable = false)
    private String comunaOrigen;

    @Column(name = "resolucion_numero", nullable = false)
    private Integer resolucionNumero;

    @Column(name = "resolucion_fecha", nullable = false)
    private LocalDate resolucionFecha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Ambiente ambiente;

    @Column(name = "cert_env_var", nullable = false)
    private String certEnvVar;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
