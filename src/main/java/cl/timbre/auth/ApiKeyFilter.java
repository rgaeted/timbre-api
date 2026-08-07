package cl.timbre.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/api/v1/health".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String plainKey = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim()
                : null;

        var emisor = apiKeyService.resolve(plainKey);
        if (emisor.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"codigo\":\"no_autenticado\",\"mensaje\":\"API key invalida o ausente\"}");
            return;
        }

        try {
            EmisorContext.set(emisor.get());
            chain.doFilter(request, response);
        } finally {
            // Imprescindible: el pool reutiliza hilos entre requests.
            EmisorContext.clear();
        }
    }
}
