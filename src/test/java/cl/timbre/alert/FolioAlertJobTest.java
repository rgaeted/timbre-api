package cl.timbre.alert;

import cl.timbre.caf.FolioAssigner;
import cl.timbre.domain.Emisor;
import cl.timbre.domain.FolioAlert;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.repository.FolioAlertRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FolioAlertJobTest {

    @Mock
    private EmisorRepository emisorRepository;

    @Mock
    private FolioAssigner folioAssigner;

    @Mock
    private FolioAlertRepository folioAlertRepository;

    @Mock
    private FolioAlertService folioAlertService;

    @InjectMocks
    private FolioAlertJob job;

    @Test
    void verificarYAlertar_sends_alert_when_folios_below_threshold() {
        Emisor emisor = new Emisor();
        emisor.setId("76123456");
        emisor.setRazonSocial("Test Company");
        emisor.setEmail("test@example.com");

        when(emisorRepository.findAll()).thenReturn(List.of(emisor));
        when(folioAssigner.disponibles("76123456", 33)).thenReturn(15);  // below threshold
        when(folioAssigner.disponibles("76123456", 61)).thenReturn(30);
        when(folioAlertRepository.findByEmisorIdAndTipoDte("76123456", 33))
                .thenReturn(Optional.empty());  // no recent alert
        when(folioAlertService.enviarAlerta(eq(emisor), eq(33), anyMap())).thenReturn(true);

        job.verificarYAlertar();

        verify(folioAlertService).enviarAlerta(eq(emisor), eq(33), anyMap());
        verify(folioAlertRepository).save(any(FolioAlert.class));
    }

    @Test
    void verificarYAlertar_does_not_save_record_when_send_fails() {
        Emisor emisor = new Emisor();
        emisor.setId("76123456");
        emisor.setRazonSocial("Test Company");
        emisor.setEmail("test@example.com");

        when(emisorRepository.findAll()).thenReturn(List.of(emisor));
        when(folioAssigner.disponibles("76123456", 33)).thenReturn(15);  // below threshold
        when(folioAssigner.disponibles("76123456", 61)).thenReturn(30);
        when(folioAlertRepository.findByEmisorIdAndTipoDte("76123456", 33))
                .thenReturn(Optional.empty());  // no recent alert
        when(folioAlertService.enviarAlerta(eq(emisor), eq(33), anyMap())).thenReturn(false);

        job.verificarYAlertar();

        verify(folioAlertService).enviarAlerta(eq(emisor), eq(33), anyMap());
        verify(folioAlertRepository, never()).save(any(FolioAlert.class));
    }

    @Test
    void verificarYAlertar_resends_alert_after_24h_window_expires() {
        Emisor emisor = new Emisor();
        emisor.setId("76123456");
        emisor.setRazonSocial("Test Company");
        emisor.setEmail("test@example.com");

        Instant staleTimestamp = Instant.now().minusSeconds(25 * 3600);  // 25 hours ago
        FolioAlert staleAlert = new FolioAlert();
        staleAlert.setId("existing-id");
        staleAlert.setEmisorId("76123456");
        staleAlert.setTipoDte(33);
        staleAlert.setLastAlertSentAt(staleTimestamp);

        when(emisorRepository.findAll()).thenReturn(List.of(emisor));
        when(folioAssigner.disponibles("76123456", 33)).thenReturn(15);  // below threshold
        when(folioAssigner.disponibles("76123456", 61)).thenReturn(30);
        when(folioAlertRepository.findByEmisorIdAndTipoDte("76123456", 33))
                .thenReturn(Optional.of(staleAlert));
        when(folioAlertService.enviarAlerta(eq(emisor), eq(33), anyMap())).thenReturn(true);

        job.verificarYAlertar();

        verify(folioAlertService).enviarAlerta(eq(emisor), eq(33), anyMap());

        ArgumentCaptor<FolioAlert> captor = ArgumentCaptor.forClass(FolioAlert.class);
        verify(folioAlertRepository).save(captor.capture());
        assertThat(captor.getValue().getLastAlertSentAt()).isAfter(staleTimestamp);
        assertThat(captor.getValue().getId()).isEqualTo("existing-id");
    }

    @Test
    void verificarYAlertar_isolates_per_emisor_errors_so_other_emisores_still_processed() {
        Emisor emisorConError = new Emisor();
        emisorConError.setId("11111111");
        emisorConError.setRazonSocial("Emisor Con Error");
        emisorConError.setEmail("error@example.com");

        Emisor emisorOk = new Emisor();
        emisorOk.setId("22222222");
        emisorOk.setRazonSocial("Emisor OK");
        emisorOk.setEmail("ok@example.com");

        when(emisorRepository.findAll()).thenReturn(List.of(emisorConError, emisorOk));

        // First emisor: the repository call blows up while processing (not just the
        // folioAssigner call, which already has its own inner try/catch).
        when(folioAssigner.disponibles("11111111", 33)).thenReturn(5);  // below threshold
        when(folioAssigner.disponibles("11111111", 61)).thenReturn(30);
        when(folioAlertRepository.findByEmisorIdAndTipoDte("11111111", 33))
                .thenThrow(new RuntimeException("DB connection lost"));

        // Second emisor: should still be fully processed despite the first one failing.
        when(folioAssigner.disponibles("22222222", 33)).thenReturn(10);  // below threshold
        when(folioAssigner.disponibles("22222222", 61)).thenReturn(30);
        when(folioAlertRepository.findByEmisorIdAndTipoDte("22222222", 33))
                .thenReturn(Optional.empty());
        when(folioAlertService.enviarAlerta(eq(emisorOk), eq(33), anyMap())).thenReturn(true);

        job.verificarYAlertar();

        verify(folioAlertService, never()).enviarAlerta(eq(emisorConError), anyInt(), anyMap());
        verify(folioAlertService).enviarAlerta(eq(emisorOk), eq(33), anyMap());
        verify(folioAlertRepository).save(any(FolioAlert.class));
    }

    @Test
    void verificarYAlertar_skips_alert_if_already_sent_within_24h() {
        Emisor emisor = new Emisor();
        emisor.setId("76123456");
        emisor.setRazonSocial("Test Company");

        FolioAlert recentAlert = new FolioAlert();
        recentAlert.setLastAlertSentAt(Instant.now().minusSeconds(3600));  // 1 hour ago

        when(emisorRepository.findAll()).thenReturn(List.of(emisor));
        when(folioAssigner.disponibles("76123456", 33)).thenReturn(15);  // below threshold
        when(folioAssigner.disponibles("76123456", 61)).thenReturn(30);
        when(folioAlertRepository.findByEmisorIdAndTipoDte("76123456", 33))
                .thenReturn(Optional.of(recentAlert));  // recent alert exists

        job.verificarYAlertar();

        verify(folioAlertService, never()).enviarAlerta(any(), anyInt(), anyMap());
    }
}
