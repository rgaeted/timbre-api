package cl.timbre.alert;

import cl.timbre.caf.FolioAssigner;
import cl.timbre.domain.Emisor;
import cl.timbre.domain.FolioAlert;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.repository.FolioAlertRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

        job.verificarYAlertar();

        verify(folioAlertService).enviarAlerta(eq(emisor), eq(33), anyMap());
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
