package cl.timbre.repository;

import cl.timbre.domain.Document;
import cl.timbre.domain.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {

    Optional<Document> findByEmisorIdAndExternalId(String emisorId, String externalId);

    Optional<Document> findByIdAndEmisorId(String id, String emisorId);

    List<Document> findByEstadoInAndProximaConsultaAtBefore(
            List<DocumentStatus> estados, Instant limite);
}
