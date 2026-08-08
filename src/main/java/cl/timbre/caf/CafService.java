package cl.timbre.caf;

import cl.timbre.domain.Emisor;
import cl.timbre.domain.FolioRange;
import cl.timbre.exception.ApiException;
import cl.timbre.repository.FolioRangeRepository;
import cl.timbre.rut.RutValidator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CafService {

    private static final List<Integer> TIPOS_SOPORTADOS = List.of(33, 61);

    private final FolioRangeRepository folioRangeRepository;

    public CafService(FolioRangeRepository folioRangeRepository) {
        this.folioRangeRepository = folioRangeRepository;
    }

    @Transactional
    public FolioRange register(Emisor emisor, byte[] cafXml) {
        Caf caf;
        try {
            caf = CafParser.parse(cafXml);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "caf_invalido", e.getMessage());
        }

        if (!RutValidator.normalize(caf.rutEmisor()).equals(RutValidator.normalize(emisor.getRut()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "caf_otro_rut",
                    "El CAF es del RUT " + caf.rutEmisor() + " y el emisor es " + emisor.getRut());
        }
        if (!TIPOS_SOPORTADOS.contains(caf.tipoDte())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "caf_tipo_no_soportado",
                    "Tipo de documento no soportado: " + caf.tipoDte());
        }
        if (folioRangeRepository.existsByEmisorIdAndTipoDteAndFolioDesde(
                emisor.getId(), caf.tipoDte(), caf.desde())) {
            throw new ApiException(HttpStatus.CONFLICT, "caf_duplicado", "Ese CAF ya estaba cargado");
        }
        // Falla temprano si la llave no se puede leer, en vez de al emitir.
        CafParser.privateKey(caf);

        FolioRange range = FolioRange.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .tipoDte(caf.tipoDte())
                .folioDesde(caf.desde())
                .folioHasta(caf.hasta())
                .folioActual(caf.desde() - 1)
                .cafXml(caf.cafElementXml())
                .privateKeyPem(caf.privateKeyPem())
                .fechaAutorizacion(caf.fechaAutorizacion())
                .agotado(false)
                .build();

        return folioRangeRepository.save(range);
    }
}
