package cl.timbre.alert;

import cl.timbre.config.FolioAlertProperties;
import cl.timbre.domain.Emisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FolioAlertService {

    private static final Logger log = LoggerFactory.getLogger(FolioAlertService.class);

    private final JavaMailSender mailSender;
    private final FolioAlertProperties properties;

    @Value("${timbre.admin-email:}")
    private String adminEmail;

    @Value("${timbre.mail-from:}")
    private String mailFrom;

    @Value("${timbre.folio-alert-threshold:20}")
    private int threshold;

    public FolioAlertService(JavaMailSender mailSender, FolioAlertProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void enviarAlerta(Emisor emisor, Integer tipoDteQueDisparo, Map<Integer, Integer> foliosPorTipo) {
        List<String> recipients = new ArrayList<>();

        if (adminEmail != null && !adminEmail.isBlank()) {
            recipients.add(adminEmail);
        }

        if (emisor.getEmail() != null && !emisor.getEmail().isBlank()) {
            recipients.add(emisor.getEmail());
        }

        if (recipients.isEmpty()) {
            log.warn("No recipients configured for folio alert (emisor {} has no email, admin-email not set)", emisor.getId());
            return;
        }

        String subject = "[Timbre] Alerta: Folios bajos para " + emisor.getRazonSocial();
        String body = composeEmailBody(emisor, foliosPorTipo);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(recipients.toArray(new String[0]));
        message.setSubject(subject);
        message.setText(body);

        try {
            mailSender.send(message);
            log.info("Folio alert sent to {} for emisor {} (tipoDte: {})", String.join(", ", recipients), emisor.getId(), tipoDteQueDisparo);
        } catch (Exception e) {
            log.warn("Failed to send folio alert for emisor {} (tipoDte: {}): {}", emisor.getId(), tipoDteQueDisparo, e.getMessage(), e);
        }
    }

    private String composeEmailBody(Emisor emisor, Map<Integer, Integer> foliosPorTipo) {
        StringBuilder body = new StringBuilder();
        body.append("Hola,\n\n");
        body.append("Los folios disponibles para ").append(emisor.getRazonSocial())
                .append(" están bajo el threshold configurado (").append(threshold).append(" folios mínimos).\n\n");
        body.append("Detalles por tipo de DTE:\n");

        foliosPorTipo.forEach((tipoDte, count) -> {
            String tipoNombre = tipoDte == 33 ? "Factura" : tipoDte == 61 ? "Nota de Crédito" : "Tipo " + tipoDte;
            body.append("- ").append(tipoNombre).append(" (").append(tipoDte).append("): ")
                    .append(count).append(" folios disponibles\n");
        });

        body.append("\nPor favor, sube un CAF nuevo desde el SII para continuar emitiendo.\n\n");
        body.append("---\n");
        body.append("Timbre API | ").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now())).append("\n");

        return body.toString();
    }
}
