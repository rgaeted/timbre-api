package cl.timbre.sii;

import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Emisor;
import org.springframework.stereotype.Component;

/** Resuelve la URL base del SII a usar: la del override de config si esta seteado, o la del ambiente del emisor. */
@Component
public class SiiUrlResolver {

    private final String override;

    public SiiUrlResolver(SiiProperties properties) {
        this.override = properties.baseUrl();
    }

    public String resolve(Emisor emisor) {
        return (override == null || override.isBlank())
                ? emisor.getAmbiente().baseUrl()
                : override;
    }
}
