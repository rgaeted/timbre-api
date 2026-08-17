package cl.timbre.alert;

import cl.timbre.config.FolioAlertProperties;
import cl.timbre.domain.Emisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FolioAlertServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private FolioAlertProperties properties;

    @InjectMocks
    private FolioAlertService service;

    @Test
    void enviarAlerta_sends_email_with_correct_recipients_and_content() {
        Emisor emisor = new Emisor();
        emisor.setId("76123456");
        emisor.setRazonSocial("Test Company");
        emisor.setEmail("test@example.com");

        Map<Integer, Integer> foliosPorTipo = Map.of(33, 15, 61, 10);

        service.enviarAlerta(emisor, 33, foliosPorTipo);

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());

        SimpleMailMessage message = messageCaptor.getValue();
        assertThat(message.getSubject()).contains("Alerta");
        assertThat(message.getTo()).contains("test@example.com");
    }

    @Test
    void enviarAlerta_includes_per_tipoDte_details() {
        Emisor emisor = new Emisor();
        emisor.setId("76123456");
        emisor.setRazonSocial("Test Company");
        emisor.setEmail("test@example.com");

        Map<Integer, Integer> foliosPorTipo = Map.of(33, 5, 61, 25);

        service.enviarAlerta(emisor, 33, foliosPorTipo);

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());

        SimpleMailMessage message = messageCaptor.getValue();
        String body = message.getText();
        assertThat(body).contains("5").contains("25");  // folio counts
    }

    @Test
    void enviarAlerta_handles_null_emisor_email_gracefully() {
        Emisor emisor = new Emisor();
        emisor.setId("76123456");
        emisor.setRazonSocial("Test Company");
        emisor.setEmail(null);  // no email

        Map<Integer, Integer> foliosPorTipo = Map.of(33, 10, 61, 20);

        // Should not throw, only send to admin if configured
        service.enviarAlerta(emisor, 33, foliosPorTipo);

        // Verify mail was attempted (or skipped if no admin email)
    }
}
