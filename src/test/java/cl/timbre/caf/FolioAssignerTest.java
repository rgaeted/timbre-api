package cl.timbre.caf;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.domain.Emisor;
import cl.timbre.exception.ApiException;
import cl.timbre.repository.ApiKeyRepository;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.repository.FolioRangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolioAssignerTest extends AbstractIntegrationTest {

    @Autowired private FolioAssigner folioAssigner;
    @Autowired private CafService cafService;
    @Autowired private EmisorRepository emisorRepository;
    @Autowired private FolioRangeRepository folioRangeRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;

    private Emisor emisor;

    @BeforeEach
    void setUp() throws Exception {
        // apiKeyRepository primero: el contenedor Postgres es compartido por toda la
        // suite, así que puede quedar una api_key de otra clase de test apuntando a un
        // emisor que aquí estamos por borrar (viola la FK si no se limpia primero).
        apiKeyRepository.deleteAll();
        folioRangeRepository.deleteAll();
        emisorRepository.deleteAll();
        emisor = emisorRepository.save(TestFixtures.emisor());
        cafService.register(emisor, Files.readAllBytes(
                Path.of("src/test/resources/sii/caf-33-ejemplo.xml")));
    }

    @Test
    void asignaFoliosCorrelativosDesdeElInicioDelRango() {
        assertThat(folioAssigner.assign(emisor.getId(), 33).folio()).isEqualTo(1);
        assertThat(folioAssigner.assign(emisor.getId(), 33).folio()).isEqualTo(2);
        assertThat(folioAssigner.assign(emisor.getId(), 33).folio()).isEqualTo(3);
    }

    @Test
    void veinteHilosEnParaleloObtienenVeinteFoliosDistintos() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Callable<Integer>> tareas = IntStream.range(0, 20)
                .<Callable<Integer>>mapToObj(i -> () -> folioAssigner.assign(emisor.getId(), 33).folio())
                .toList();

        List<Integer> folios = pool.invokeAll(tareas).stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
        pool.shutdown();

        assertThat(folios).hasSize(20);
        assertThat(folios).doesNotHaveDuplicates();
        assertThat(folios).containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(1, 20).boxed().toList());
    }

    @Test
    void alAgotarseElRangoFallaEnVezDeReutilizar() {
        // El CAF de ejemplo va del 1 al 50.
        IntStream.rangeClosed(1, 50).forEach(i -> folioAssigner.assign(emisor.getId(), 33));

        assertThatThrownBy(() -> folioAssigner.assign(emisor.getId(), 33))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("folios");
    }

    @Test
    void cuentaLosFoliosDisponibles() {
        assertThat(folioAssigner.disponibles(emisor.getId(), 33)).isEqualTo(50);
        folioAssigner.assign(emisor.getId(), 33);
        assertThat(folioAssigner.disponibles(emisor.getId(), 33)).isEqualTo(49);
    }

    @Test
    void rechazaUnCafDeOtroRut() throws Exception {
        Emisor otro = emisorRepository.save(TestFixtures.emisorConRut("77777777-7"));
        byte[] caf = Files.readAllBytes(Path.of("src/test/resources/sii/caf-33-ejemplo.xml"));

        assertThatThrownBy(() -> cafService.register(otro, caf))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("RUT");
    }
}
