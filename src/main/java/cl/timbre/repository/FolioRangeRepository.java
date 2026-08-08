package cl.timbre.repository;

import cl.timbre.domain.FolioRange;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FolioRangeRepository extends JpaRepository<FolioRange, String> {

    /**
     * Toma el rango disponible más antiguo y lo bloquea hasta el fin de la
     * transacción. Dos emisiones simultáneas se serializan aquí: la segunda
     * espera y luego relee la fila ya incrementada.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select f from FolioRange f
            where f.emisorId = :emisorId
              and f.tipoDte = :tipoDte
              and f.agotado = false
              and f.folioActual < f.folioHasta
            order by f.fechaAutorizacion asc, f.folioDesde asc
            limit 1
            """)
    Optional<FolioRange> lockNextAvailable(@Param("emisorId") String emisorId,
                                           @Param("tipoDte") int tipoDte);

    List<FolioRange> findByEmisorIdAndTipoDteOrderByFolioDesdeAsc(String emisorId, int tipoDte);

    List<FolioRange> findByEmisorIdOrderByTipoDteAscFolioDesdeAsc(String emisorId);

    boolean existsByEmisorIdAndTipoDteAndFolioDesde(String emisorId, int tipoDte, int folioDesde);
}
