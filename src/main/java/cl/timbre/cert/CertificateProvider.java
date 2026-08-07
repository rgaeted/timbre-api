package cl.timbre.cert;

import cl.timbre.domain.Emisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carga el certificado digital desde una variable de entorno en base64.
 * En Heroku el disco es efímero, así que no puede haber un archivo .p12.
 */
@Component
public class CertificateProvider {

    private final String base64;
    private final String password;
    private final Map<String, SigningMaterial> cache = new ConcurrentHashMap<>();

    public CertificateProvider(@Value("${sii.cert-p12-base64:}") String base64,
                               @Value("${sii.cert-password:}") String password) {
        this.base64 = base64;
        this.password = password;
    }

    /** Solo para tests: permite reconstruir el provider con otra clave. */
    String rawBase64() {
        return base64;
    }

    public SigningMaterial forEmisor(Emisor emisor) {
        return cache.computeIfAbsent(emisor.getId(), id -> load());
    }

    public Instant expiresAt(Emisor emisor) {
        return forEmisor(emisor).certificate().getNotAfter().toInstant();
    }

    private SigningMaterial load() {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "No hay certificado configurado: falta SII_CERT_P12_BASE64");
        }
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ByteArrayInputStream(Base64.getDecoder().decode(base64)),
                    password.toCharArray());

            String alias = firstKeyAlias(keyStore);
            PrivateKey key = (PrivateKey) keyStore.getKey(alias, password.toCharArray());
            X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias);
            return new SigningMaterial(key, cert);
        } catch (Exception e) {
            // Se descarta la causa original a propósito: puede contener la clave.
            throw new IllegalStateException(
                    "No se pudo abrir el certificado digital (revisa SII_CERT_P12_BASE64 y SII_CERT_PASSWORD)");
        }
    }

    private String firstKeyAlias(KeyStore keyStore) throws Exception {
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) {
                return alias;
            }
        }
        throw new IllegalStateException("El certificado no contiene ninguna llave privada");
    }
}
