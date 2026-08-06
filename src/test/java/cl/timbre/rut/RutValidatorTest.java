package cl.timbre.rut;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RutValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"76123456-0", "76.123.456-0", "761234560", "11111111-1", "6-K", "12345678-5"})
    void aceptaRutsValidos(String rut) {
        assertThat(RutValidator.isValid(rut)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"76123456-7", "76.123.456-K", "", "  ", "abc", "0-0", "76123456-"})
    void rechazaRutsInvalidos(String rut) {
        assertThat(RutValidator.isValid(rut)).isFalse();
    }

    @Test
    void rechazaNull() {
        assertThat(RutValidator.isValid(null)).isFalse();
    }

    @Test
    void normalizaQuitandoPuntosYSubiendoLaK() {
        assertThat(RutValidator.normalize("6-k")).isEqualTo("6-K");
        assertThat(RutValidator.normalize("761234560")).isEqualTo("76123456-0");
    }

    @Test
    void separaCuerpoYDigitoVerificador() {
        assertThat(RutValidator.body("76.123.456-0")).isEqualTo("76123456");
        assertThat(RutValidator.dv("76.123.456-0")).isEqualTo("0");
    }

    @Test
    void normalizarUnRutInvalidoFalla() {
        assertThatThrownBy(() -> RutValidator.normalize("76123456-7"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
