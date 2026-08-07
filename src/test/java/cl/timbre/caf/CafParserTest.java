package cl.timbre.caf;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CafParserTest {

    private byte[] ejemplo() throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/sii/caf-33-ejemplo.xml"));
    }

    @Test
    void extraeLosDatosDelRango() throws Exception {
        Caf caf = CafParser.parse(ejemplo());

        assertThat(caf.rutEmisor()).isEqualTo("76123456-0");
        assertThat(caf.tipoDte()).isEqualTo(33);
        assertThat(caf.desde()).isEqualTo(1);
        assertThat(caf.hasta()).isEqualTo(50);
        assertThat(caf.fechaAutorizacion()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void conservaElElementoCafCompletoParaElTimbre() throws Exception {
        Caf caf = CafParser.parse(ejemplo());

        // El <CAF> entero se embebe tal cual dentro del TED de cada documento.
        assertThat(caf.cafElementXml())
                .startsWith("<CAF")
                .contains("<RE>76123456-0</RE>")
                .contains("<FRMA")
                .endsWith("</CAF>");
    }

    @Test
    void cargaLaLlavePrivadaEnFormatoPkcs1() throws Exception {
        Caf caf = CafParser.parse(ejemplo());

        assertThat(CafParser.privateKey(caf).getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void rechazaUnXmlQueNoEsUnCaf() {
        assertThatThrownBy(() -> CafParser.parse("<hola/>".getBytes()))
                .hasMessageContaining("CAF");
    }
}
