package cl.timbre.repository;

import cl.timbre.domain.FolioAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FolioAlertRepository extends JpaRepository<FolioAlert, String> {
    Optional<FolioAlert> findByEmisorIdAndTipoDte(String emisorId, Integer tipoDte);
}
