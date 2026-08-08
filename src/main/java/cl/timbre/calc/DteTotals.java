package cl.timbre.calc;

import cl.timbre.model.DteGlobalDiscount;
import cl.timbre.model.DteLine;

import java.util.List;

public record DteTotals(
        int montoNeto,
        int iva,
        int montoTotal,
        List<DteLine> lineas,
        List<DteGlobalDiscount> descuentos
) {}
