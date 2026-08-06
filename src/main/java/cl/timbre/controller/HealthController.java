package cl.timbre.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final String ambiente;

    public HealthController(@Value("${sii.ambiente:CERTIFICACION}") String ambiente) {
        this.ambiente = ambiente;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "ambiente", ambiente);
    }
}
