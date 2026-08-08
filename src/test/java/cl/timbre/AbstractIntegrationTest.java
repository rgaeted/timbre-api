package cl.timbre;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    // static: un solo contenedor reutilizado por toda la suite, no uno por clase.
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void limpiarBaseDeDatos() {
        // TRUNCATE ... CASCADE limpia emisor y todo lo que depende de el (directa o
        // transitivamente via FK) en una sola sentencia, sin que cada subclase tenga
        // que enumerar las tablas hijas en el orden correcto. Nuevas tablas con FK a
        // emisor quedan cubiertas automaticamente, sin tocar este metodo.
        jdbcTemplate.execute("TRUNCATE TABLE emisor RESTART IDENTITY CASCADE");
    }
}
