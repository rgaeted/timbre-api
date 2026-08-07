package cl.timbre.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sii")
public record SiiProperties(
        String ambiente,
        String certP12Base64,
        String certPassword,
        int timeoutMs
) {}
