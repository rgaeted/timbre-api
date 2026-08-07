package cl.timbre.repository;

import cl.timbre.domain.Emisor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmisorRepository extends JpaRepository<Emisor, String> {

    Optional<Emisor> findByRut(String rut);
}
