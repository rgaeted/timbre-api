package cl.timbre.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sii")
public record SiiProperties(
        String ambiente,
        String certP12Base64,
        String certPassword,
        int timeoutMs,
        String baseUrl,
        int envioMaxIntentos,
        long envioBackoffMs,
        long envioJobFixedDelayMs,
        long envioConsultaDelayMs,
        int consultaMaxIntentos,
        long consultaBackoffMs,
        long consultaJobFixedDelayMs
) {}
