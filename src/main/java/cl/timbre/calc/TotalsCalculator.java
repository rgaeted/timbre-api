package cl.timbre.calc;

import cl.timbre.dto.IssueLine;
import cl.timbre.dto.LineType;
import cl.timbre.model.DteGlobalDiscount;
import cl.timbre.model.DteLine;

import java.util.ArrayList;
import java.util.List;

/**
 * Convierte líneas con precio bruto (IVA incluido, como las tiene el catálogo)
 * en el detalle neto que exige la factura 33.
 *
 * El total cobrado manda: se calcula primero y no se mueve. El neto y el IVA se
 * derivan de él, y cualquier residuo de redondeo se absorbe en un descuento o
 * recargo global, que es el mecanismo que el SII define para esto.
 */
public final class TotalsCalculator {

    private static final int IVA_PORCENTAJE = 19;

    private TotalsCalculator() {}

    public static DteTotals compute(List<IssueLine> entrada) {
        if (entrada == null || entrada.isEmpty()) {
            throw new IllegalArgumentException("El documento no tiene lineas");
        }

        // 1. El total es lo que se cobro. Intocable.
        int montoTotal = entrada.stream()
                .mapToInt(l -> l.cantidad() * l.precioUnitarioBruto())
                .sum();
        if (montoTotal <= 0) {
            throw new IllegalArgumentException("El total del documento debe ser mayor que cero");
        }

        // 2. Neto e IVA derivados: por construccion suman exactamente el total.
        int montoNeto = divideHalfUp((long) montoTotal * 100, 100 + IVA_PORCENTAJE);
        int iva = montoTotal - montoNeto;

        // 3. Detalle y descuentos, cada uno con su redondeo propio.
        List<DteLine> lineas = new ArrayList<>();
        List<DteGlobalDiscount> descuentos = new ArrayList<>();
        int numeroLinea = 1;
        int numeroDescuento = 1;

        for (IssueLine linea : entrada) {
            int brutoLinea = linea.cantidad() * linea.precioUnitarioBruto();
            if (linea.tipo() == LineType.DESCUENTO || brutoLinea < 0) {
                int valorNeto = divideHalfUp(Math.abs((long) brutoLinea) * 100, 100 + IVA_PORCENTAJE);
                descuentos.add(new DteGlobalDiscount(
                        numeroDescuento++, linea.descripcion(), 'D', valorNeto));
            } else {
                int precioNeto = divideHalfUp((long) linea.precioUnitarioBruto() * 100,
                        100 + IVA_PORCENTAJE);
                lineas.add(new DteLine(numeroLinea++, linea.descripcion(),
                        linea.cantidad(), precioNeto, precioNeto * linea.cantidad()));
            }
        }

        // 4. El residuo se absorbe como descuento o recargo global.
        int sumaDetalle = lineas.stream().mapToInt(DteLine::montoNeto).sum();
        int sumaDescuentos = descuentos.stream().mapToInt(DteGlobalDiscount::valor).sum();
        int residuo = montoNeto - (sumaDetalle - sumaDescuentos);

        if (residuo != 0) {
            // residuo > 0 -> falta neto -> recargo. residuo < 0 -> sobra -> descuento.
            descuentos.add(new DteGlobalDiscount(
                    numeroDescuento,
                    "Ajuste de redondeo",
                    residuo > 0 ? 'R' : 'D',
                    Math.abs(residuo)));
        }

        return new DteTotals(montoNeto, iva, montoTotal,
                List.copyOf(lineas), List.copyOf(descuentos));
    }

    /** Division entera con redondeo half-up, que es la convencion del SII para CLP. */
    private static int divideHalfUp(long numerador, long denominador) {
        return Math.toIntExact((numerador + denominador / 2) / denominador);
    }
}
