package cl.timbre.auth;

import cl.timbre.domain.Emisor;
import cl.timbre.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Emisor resuelto para el request en curso. Lo puebla {@link ApiKeyFilter}. */
public final class EmisorContext {

    private static final ThreadLocal<Emisor> CURRENT = new ThreadLocal<>();

    private EmisorContext() {}

    static void set(Emisor emisor) {
        CURRENT.set(emisor);
    }

    static void clear() {
        CURRENT.remove();
    }

    public static Emisor current() {
        Emisor emisor = CURRENT.get();
        if (emisor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "no_autenticado", "Falta la API key");
        }
        return emisor;
    }
}
