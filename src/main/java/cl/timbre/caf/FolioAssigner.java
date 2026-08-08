package cl.timbre.caf;

import cl.timbre.domain.FolioRange;
import cl.timbre.exception.ApiException;
import cl.timbre.repository.FolioRangeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FolioAssigner {

    public record AssignedFolio(int folio, FolioRange range) {}

    private final FolioRangeRepository folioRangeRepository;

    public FolioAssigner(FolioRangeRepository folioRangeRepository) {
        this.folioRangeRepository = folioRangeRepository;
    }

    /**
     * Asigna el siguiente folio disponible. Corre en su propia transacción para
     * que el bloqueo se libere apenas se incrementa el contador, y no quede
     * tomado durante la firma y el envío al SII.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssignedFolio assign(String emisorId, int tipoDte) {
        FolioRange range = folioRangeRepository.lockNextAvailable(emisorId, tipoDte)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "sin_folios",
                        "No quedan folios disponibles para el tipo " + tipoDte
                                + ". Sube un CAF nuevo desde el SII."));

        int folio = range.getFolioActual() + 1;
        range.setFolioActual(folio);
        if (folio >= range.getFolioHasta()) {
            range.setAgotado(true);
        }
        folioRangeRepository.save(range);

        return new AssignedFolio(folio, range);
    }

    @Transactional(readOnly = true)
    public int disponibles(String emisorId, int tipoDte) {
        return folioRangeRepository.findByEmisorIdAndTipoDteOrderByFolioDesdeAsc(emisorId, tipoDte)
                .stream()
                .filter(r -> !Boolean.TRUE.equals(r.getAgotado()))
                .mapToInt(r -> r.getFolioHasta() - r.getFolioActual())
                .sum();
    }
}
