package cl.timbre.model;

import java.time.LocalDate;

/** Referencia a otro documento. En la NC apunta al documento que corrige. */
public record DteReference(int numero, int tipoDocRef, int folioRef,
                           LocalDate fechaRef, int codigoRef, String razonRef) {}
