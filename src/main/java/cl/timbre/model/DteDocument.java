package cl.timbre.model;

import cl.timbre.calc.DteTotals;
import cl.timbre.domain.Emisor;

import java.time.LocalDate;
import java.util.List;

public record DteDocument(
        Emisor emisor,
        DteReceptor receptor,
        int tipoDte,
        int folio,
        LocalDate fechaEmision,
        DteTotals totales,
        List<DteReference> referencias
) {}
