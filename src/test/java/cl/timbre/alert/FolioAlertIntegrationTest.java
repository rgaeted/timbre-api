package cl.timbre.alert;

import cl.timbre.AbstractIntegrationTest;
import cl.timbre.TestFixtures;
import cl.timbre.domain.Emisor;
import cl.timbre.domain.FolioAlert;
import cl.timbre.domain.FolioRange;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.repository.FolioAlertRepository;
import cl.timbre.repository.FolioRangeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for {@link FolioAlertJob}, exercising the real
 * PostgreSQL testcontainer wired by {@link AbstractIntegrationTest}.
 *
 * Verifies that the job creates a {@link FolioAlert} record when an emisor's
 * available folios drop below the configured threshold, and that it skips
 * sending a duplicate alert if one was already sent within the last 24h.
 */
@SpringBootTest
class FolioAlertIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EmisorRepository emisorRepository;

    @Autowired
    private FolioRangeRepository folioRangeRepository;

    @Autowired
    private FolioAlertRepository folioAlertRepository;

    @Autowired
    private FolioAlertJob folioAlertJob;

    private Emisor crearEmisorConEmail(String email) {
        Emisor emisor = TestFixtures.emisor();
        emisor.setEmail(email);
        return emisorRepository.save(emisor);
    }

    private FolioRange crearFolioRange(String emisorId, int tipoDte, int folioDesde, int folioHasta, int folioActual) {
        FolioRange range = FolioRange.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisorId)
                .tipoDte(tipoDte)
                .folioDesde(folioDesde)
                .folioHasta(folioHasta)
                .folioActual(folioActual)
                .cafXml("<CAF>test</CAF>")
                .privateKeyPem("-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----")
                .fechaAutorizacion(java.time.LocalDate.of(2024, 1, 1))
                .agotado(false)
                .build();
        return folioRangeRepository.save(range);
    }

    @Test
    void folioAlertJob_creates_alert_record_when_folios_below_threshold() {
        // Setup: emisor with few folios (15 available, below default threshold of 20)
        Emisor emisor = crearEmisorConEmail("test@example.com");
        crearFolioRange(emisor.getId(), 33, 1000, 1020, 1005); // 15 folios available

        // Execute
        folioAlertJob.verificarYAlertar();

        // Verify: FolioAlert record created
        Optional<FolioAlert> alert = folioAlertRepository.findByEmisorIdAndTipoDte(emisor.getId(), 33);
        assertThat(alert).isPresent();
        assertThat(alert.get().getLastAlertSentAt()).isNotNull();
    }

    @Test
    void folioAlertJob_skips_duplicate_alert_within_24h() {
        Emisor emisor = crearEmisorConEmail("test@example.com");
        crearFolioRange(emisor.getId(), 33, 1000, 1015, 1005); // 10 folios available

        // Create existing alert (sent 1 hour ago). Truncated to microseconds to match
        // the precision of the Postgres TIMESTAMP column, so the round-tripped value
        // compares equal to the original.
        Instant unaHoraAtras = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        FolioAlert existingAlert = FolioAlert.builder()
                .id(UUID.randomUUID().toString())
                .emisorId(emisor.getId())
                .tipoDte(33)
                .lastAlertSentAt(unaHoraAtras)
                .createdAt(unaHoraAtras)
                .updatedAt(unaHoraAtras)
                .build();
        folioAlertRepository.save(existingAlert);

        // Execute job
        folioAlertJob.verificarYAlertar();

        // Verify: alert was NOT updated (still same timestamp)
        Optional<FolioAlert> alertAfter = folioAlertRepository.findByEmisorIdAndTipoDte(emisor.getId(), 33);
        assertThat(alertAfter).isPresent();
        assertThat(alertAfter.get().getLastAlertSentAt()).isEqualTo(unaHoraAtras);
    }
}
