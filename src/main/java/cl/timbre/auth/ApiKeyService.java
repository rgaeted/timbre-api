package cl.timbre.auth;

import cl.timbre.domain.ApiKey;
import cl.timbre.domain.Emisor;
import cl.timbre.repository.ApiKeyRepository;
import cl.timbre.repository.EmisorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApiKeyService {

    /** Resultado de crear una key. {@code plainKey} es irrecuperable después. */
    public record GeneratedKey(String id, String plainKey) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;
    private final EmisorRepository emisorRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, EmisorRepository emisorRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.emisorRepository = emisorRepository;
    }

    @Transactional
    public GeneratedKey generate(String emisorId, String nombre) {
        byte[] material = new byte[32];
        RANDOM.nextBytes(material);
        String plainKey = "tmb_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(material);

        ApiKey key = ApiKey.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisorId)
                .nombre(nombre)
                .prefijo(plainKey.substring(0, 16))
                .hash(hash(plainKey))
                .createdAt(Instant.now())
                .build();
        apiKeyRepository.save(key);

        return new GeneratedKey(key.getId(), plainKey);
    }

    @Transactional(readOnly = true)
    public Optional<Emisor> resolve(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return Optional.empty();
        }
        return apiKeyRepository.findByHashAndRevocadaAtIsNull(hash(plainKey))
                .flatMap(k -> emisorRepository.findById(k.getEmisorId()));
    }

    @Transactional
    public void revoke(String apiKeyId) {
        apiKeyRepository.findById(apiKeyId).ifPresent(k -> {
            k.setRevocadaAt(Instant.now());
            apiKeyRepository.save(k);
        });
    }

    /**
     * SHA-256 sin salt, a propósito: la key tiene 256 bits de entropía, así que
     * no es atacable por diccionario y necesitamos una búsqueda por igualdad
     * en la base. Un hash lento tipo bcrypt obligaría a recorrer todas las keys.
     */
    private String hash(String plainKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plainKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
