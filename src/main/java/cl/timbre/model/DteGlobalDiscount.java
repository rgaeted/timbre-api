package cl.timbre.model;

/** Descuento ('D') o recargo ('R') global sobre el neto del documento. */
public record DteGlobalDiscount(int numero, String glosa, char tipoMovimiento, int valor) {}
