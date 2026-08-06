package cl.timbre.rut;

/** Validación y normalización de RUT chileno mediante módulo 11. */
public final class RutValidator {

    private RutValidator() {}

    public static boolean isValid(String rut) {
        String clean = clean(rut);
        if (clean == null) {
            return false;
        }
        String cuerpo = clean.substring(0, clean.length() - 1);
        char dv = clean.charAt(clean.length() - 1);
        // Un cuerpo de ceros ("0-0") no es un RUT real.
        if (cuerpo.chars().allMatch(c -> c == '0')) {
            return false;
        }
        return dv == computeDv(cuerpo);
    }

    public static String normalize(String rut) {
        requireValid(rut);
        String clean = clean(rut);
        return clean.substring(0, clean.length() - 1) + "-" + clean.charAt(clean.length() - 1);
    }

    public static String body(String rut) {
        requireValid(rut);
        String clean = clean(rut);
        return clean.substring(0, clean.length() - 1);
    }

    public static String dv(String rut) {
        requireValid(rut);
        String clean = clean(rut);
        return String.valueOf(clean.charAt(clean.length() - 1));
    }

    /** Quita puntos y guion, sube la K. Devuelve null si no queda algo con forma de RUT. */
    private static String clean(String rut) {
        if (rut == null) {
            return null;
        }
        String clean = rut.replace(".", "").replace("-", "").trim().toUpperCase();
        if (clean.length() < 2) {
            return null;
        }
        String cuerpo = clean.substring(0, clean.length() - 1);
        char dv = clean.charAt(clean.length() - 1);
        if (!cuerpo.chars().allMatch(Character::isDigit)) {
            return null;
        }
        if (!Character.isDigit(dv) && dv != 'K') {
            return null;
        }
        return clean;
    }

    private static void requireValid(String rut) {
        if (!isValid(rut)) {
            throw new IllegalArgumentException("RUT invalido: " + rut);
        }
    }

    /** Módulo 11: se recorre de derecha a izquierda con multiplicadores 2..7 cíclicos. */
    private static char computeDv(String cuerpo) {
        int suma = 0;
        int multiplicador = 2;
        for (int i = cuerpo.length() - 1; i >= 0; i--) {
            suma += (cuerpo.charAt(i) - '0') * multiplicador;
            multiplicador = multiplicador == 7 ? 2 : multiplicador + 1;
        }
        int resto = 11 - (suma % 11);
        if (resto == 11) {
            return '0';
        }
        if (resto == 10) {
            return 'K';
        }
        return (char) ('0' + resto);
    }
}
