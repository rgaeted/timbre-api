package cl.timbre.repository;

import cl.timbre.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {

    Optional<ApiKey> findByHashAndRevocadaAtIsNull(String hash);
}
