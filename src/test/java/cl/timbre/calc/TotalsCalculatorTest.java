package cl.timbre.calc;

import cl.timbre.dto.IssueLine;
import cl.timbre.dto.LineType;
import cl.timbre.model.DteGlobalDiscount;
import cl.timbre.model.DteLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TotalsCalculatorTest {

    private static IssueLine afecto(String d, int cantidad, int precio) {
        return new IssueLine(d, cantidad, precio, LineType.AFECTO);
    }

    private static IssueLine descuento(String d, int precio) {
        return new IssueLine(d, 1, precio, LineType.DESCUENTO);
    }

    @Test
    void calculaNetoEIvaDesdePreciosBrutos() {
        DteTotals t = TotalsCalculator.compute(List.of(afecto("Generador", 1, 1190000)));

        assertThat(t.montoTotal()).isEqualTo(1190000);
        assertThat(t.montoNeto()).isEqualTo(1000000);
        assertThat(t.iva()).isEqualTo(190000);
    }

    @Test
    void losDescuentosNoSonLineasDeDetalle() {
        DteTotals t = TotalsCalculator.compute(List.of(
                afecto("Generador", 1, 899990),
                afecto("Despacho", 1, 45000),
                descuento("Cupon INVIERNO10", -89999)));

        assertThat(t.lineas()).hasSize(2);
        assertThat(t.lineas()).noneMatch(l -> l.montoNeto() < 0);
        assertThat(t.descuentos()).isNotEmpty();
        assertThat(t.descuentos()).allMatch(d -> d.valor() > 0);
        assertThat(t.montoTotal()).isEqualTo(899990 + 45000 - 89999);
    }

    @Test
    void multiplicaDespuesDeRedondearElPrecioUnitario() {
        // 3 x 11900 bruto: neto unitario 10000, monto de linea 30000.
        DteTotals t = TotalsCalculator.compute(List.of(afecto("Filtro", 3, 11900)));

        assertThat(t.lineas().get(0).precioUnitarioNeto()).isEqualTo(10000);
        assertThat(t.lineas().get(0).montoNeto()).isEqualTo(30000);
    }

    @Test
    void numeraLasLineasDesdeUno() {
        DteTotals t = TotalsCalculator.compute(List.of(
                afecto("A", 1, 1000), afecto("B", 1, 2000)));

        assertThat(t.lineas()).extracting(DteLine::numero).containsExactly(1, 2);
    }

    /**
     * Propiedad central: sobre miles de combinaciones aleatorias, el total nunca
     * se desvía de lo cobrado y el detalle siempre cuadra con el neto.
     * Semilla fija para que cualquier fallo sea reproducible.
     */
    @Test
    void invariantesSobreCombinacionesAleatorias() {
        Random random = new Random(20260806L);

        for (int caso = 0; caso < 5000; caso++) {
            List<IssueLine> lineas = new ArrayList<>();
            int cantidadLineas = 1 + random.nextInt(8);
            int brutoAcumulado = 0;

            for (int i = 0; i < cantidadLineas; i++) {
                int cantidad = 1 + random.nextInt(5);
                int precio = 1 + random.nextInt(2_000_000);
                lineas.add(afecto("Item " + i, cantidad, precio));
                brutoAcumulado += cantidad * precio;
            }
            if (random.nextBoolean() && brutoAcumulado > 1) {
                lineas.add(descuento("Descuento", -(1 + random.nextInt(brutoAcumulado - 1))));
            }

            DteTotals t = TotalsCalculator.compute(lineas);
            int totalEsperado = lineas.stream()
                    .mapToInt(l -> l.cantidad() * l.precioUnitarioBruto())
                    .sum();

            assertThat(t.montoTotal())
                    .as("caso %d: el total debe ser lo cobrado", caso)
                    .isEqualTo(totalEsperado);
            assertThat(t.montoNeto() + t.iva())
                    .as("caso %d: neto + IVA debe cuadrar con el total", caso)
                    .isEqualTo(t.montoTotal());

            int sumaDetalle = t.lineas().stream().mapToInt(DteLine::montoNeto).sum();
            int sumaDescuentos = t.descuentos().stream()
                    .mapToInt(d -> d.tipoMovimiento() == 'D' ? d.valor() : -d.valor())
                    .sum();
            assertThat(sumaDetalle - sumaDescuentos)
                    .as("caso %d: detalle menos descuentos debe dar el neto", caso)
                    .isEqualTo(t.montoNeto());

            assertThat(t.lineas())
                    .as("caso %d: ninguna linea puede ser negativa", caso)
                    .allMatch(l -> l.montoNeto() >= 0);
        }
    }
}
