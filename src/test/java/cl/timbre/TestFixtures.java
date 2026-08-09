package cl.timbre;

import cl.timbre.domain.Ambiente;
import cl.timbre.domain.Emisor;

import java.time.LocalDate;
import java.util.UUID;

/** Datos de prueba reutilizables entre paquetes de test. */
public final class TestFixtures {

    private TestFixtures() {}

    public static Emisor emisor() {
        return emisorConRut("76123456-0");
    }

    public static Emisor emisorConRut(String rut) {
        return Emisor.builder()
                .id(UUID.randomUUID().toString())
                .rut(rut)
                .rutEnvia("11111111-1")
                .razonSocial("Volterra Equipos SpA")
                .giro("Venta de generadores electricos")
                .acteco(465100)
                .direccionOrigen("Av. Siempre Viva 742")
                .comunaOrigen("Santiago")
                .resolucionNumero(80)
                .resolucionFecha(LocalDate.of(2014, 8, 22))
                .ambiente(Ambiente.CERTIFICACION)
                .certEnvVar("SII_CERT_P12_BASE64")
                .build();
    }
}
