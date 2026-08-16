# Phase E: Email Alerts for Low Folios — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a daily scheduled job that monitors folio availability per emisor and sends email alerts when folios fall below threshold, with 24-hour deduplication to prevent alert spam.

**Architecture:** Job runs daily via cron, iterates all emisores, queries FolioAssigner.disponibles() per tipoDte, compares to threshold, sends email to admin + emisor if below threshold and no recent alert, tracks alert state in FolioAlert table.

**Tech Stack:** Spring Boot 3.4.3, Spring Mail, Spring Scheduler (cron), PostgreSQL, JPA/Hibernate, JUnit 5, Mockito.

## Global Constraints

- Java 21, Spring Boot 3.4.3
- Spring Mail dependency already in pom.xml (spring-boot-starter-mail)
- No new external dependencies
- Email sent to: `timbre.admin-email` + `Emisor.email` (if set)
- Deduplication: 24-hour window (one alert per emisor/tipoDte per day)
- Supported tipoDte: 33 (Factura), 61 (Nota de Crédito)
- Folios checked via `FolioAssigner.disponibles(emisorId, tipoDte)` (already implemented)
- Alert threshold: `timbre.folio-alert-threshold` config (default 20)
- Cron schedule: `timbre.folio-alert.cron` (default `0 6 * * *` = 6 AM UTC daily)
- Errors are non-blocking (log warning, continue job)

---

### Task 1: Add Email Column to Emisor + Flyway Migration

**Files:**
- Modify: `src/main/java/cl/timbre/domain/Emisor.java:1-100` (add email field)
- Create: `src/main/resources/db/migration/V9__Add_email_to_emisor_and_create_folio_alert.sql`

**Interfaces:**
- Consumes: Existing Emisor entity (already has id, rutSinDv, razonSocial, etc.)
- Produces: Emisor with new nullable `email` field (String, VARCHAR(255))

- [ ] **Step 1: Read Emisor entity to understand current structure**

Run: `Read src/main/java/cl/timbre/domain/Emisor.java`

Current fields: id, rutSinDv, razonSocial, representanteRut, etc. Entity uses Lombok @Getter @Setter @Builder.

- [ ] **Step 2: Add email field to Emisor entity**

```java
@Column(name = "email")
private String email;  // nullable, user can update if needed
```

Add after existing fields (before createdAt/updatedAt if they exist). Keep Lombok annotations as-is.

- [ ] **Step 3: Create Flyway migration SQL file**

File: `src/main/resources/db/migration/V9__Add_email_to_emisor_and_create_folio_alert.sql`

```sql
-- Add email column to emisor table
ALTER TABLE emisor ADD COLUMN email VARCHAR(255);

-- Create folio_alert table for tracking last alert sent per emisor/tipoDte
CREATE TABLE folio_alert (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    emisor_id VARCHAR(255) NOT NULL,
    tipo_dte INTEGER NOT NULL,
    last_alert_sent_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(emisor_id, tipo_dte),
    FOREIGN KEY(emisor_id) REFERENCES emisor(id) ON DELETE CASCADE
);

CREATE INDEX idx_folio_alert_emisor_tipo ON folio_alert(emisor_id, tipo_dte);
```

- [ ] **Step 4: Verify migration file is valid**

Run: `ls -la src/main/resources/db/migration/ | grep V9`

Expected: V9__Add_email_to_emisor_and_create_folio_alert.sql present.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cl/timbre/domain/Emisor.java \
         src/main/resources/db/migration/V9__Add_email_to_emisor_and_create_folio_alert.sql
git commit -m "feat: add email column to Emisor and create FolioAlert table (phase E)"
```

---

### Task 2: Create FolioAlert Entity

**Files:**
- Create: `src/main/java/cl/timbre/domain/FolioAlert.java`

**Interfaces:**
- Consumes: Nothing (core entity)
- Produces: FolioAlert entity with fields: id (UUID), emisorId (String), tipoDte (int), lastAlertSentAt (Instant), createdAt (Instant), updatedAt (Instant)

- [ ] **Step 1: Create FolioAlert entity file**

```java
package cl.timbre.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "folio_alert")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FolioAlert {
    @Id
    private String id;  // UUID as string

    @Column(name = "emisor_id", nullable = false)
    private String emisorId;

    @Column(name = "tipo_dte", nullable = false)
    private Integer tipoDte;

    @Column(name = "last_alert_sent_at", nullable = false)
    private Instant lastAlertSentAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
```

- [ ] **Step 2: Verify syntax**

Run: `cd src/main/java/cl/timbre/domain && javac FolioAlert.java 2>&1 || echo "Check imports if needed"`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/cl/timbre/domain/FolioAlert.java
git commit -m "feat: create FolioAlert entity (phase E)"
```

---

### Task 3: Create FolioAlertRepository

**Files:**
- Create: `src/main/java/cl/timbre/repository/FolioAlertRepository.java`

**Interfaces:**
- Consumes: FolioAlert entity (from Task 2)
- Produces: FolioAlertRepository with methods:
  - `findByEmisorIdAndTipoDte(emisorId, tipoDte) -> Optional<FolioAlert>`
  - `save(FolioAlert) -> FolioAlert` (inherited from JpaRepository)

- [ ] **Step 1: Create repository interface**

```java
package cl.timbre.repository;

import cl.timbre.domain.FolioAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FolioAlertRepository extends JpaRepository<FolioAlert, String> {
    Optional<FolioAlert> findByEmisorIdAndTipoDte(String emisorId, Integer tipoDte);
}
```

- [ ] **Step 2: Verify no syntax errors**

Run: `grep -n "FolioAlertRepository" src/main/java/cl/timbre/repository/FolioAlertRepository.java`

Expected: File created with correct class name.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/cl/timbre/repository/FolioAlertRepository.java
git commit -m "feat: create FolioAlertRepository (phase E)"
```

---

### Task 4: Create FolioAlertProperties Config Class

**Files:**
- Create: `src/main/java/cl/timbre/config/FolioAlertProperties.java`

**Interfaces:**
- Consumes: Nothing (Spring config)
- Produces: FolioAlertProperties with fields: enabled (boolean, default true), cron (String, default "0 6 * * *")

- [ ] **Step 1: Create config properties class**

```java
package cl.timbre.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "timbre.folio-alert")
public class FolioAlertProperties {
    private boolean enabled = true;
    private String cron = "0 6 * * *";  // Daily at 6 AM UTC

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }
}
```

- [ ] **Step 2: Verify class created**

Run: `grep -n "class FolioAlertProperties" src/main/java/cl/timbre/config/FolioAlertProperties.java`

Expected: Class definition found.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/cl/timbre/config/FolioAlertProperties.java
git commit -m "feat: create FolioAlertProperties config class (phase E)"
```

---

### Task 5: Create FolioAlertService (Email Composition and Send)

**Files:**
- Create: `src/main/java/cl/timbre/alert/FolioAlertService.java`
- Test: `src/test/java/cl/timbre/alert/FolioAlertServiceTest.java`

**Interfaces:**
- Consumes: Spring Mail JavaMailSender, FolioAlertProperties
- Produces: FolioAlertService with method:
  - `enviarAlerta(emisor: Emisor, tipoDte: int, folio: Map<Integer, Integer>) -> void` (throws no checked exceptions, logs errors)

- [ ] **Step 1: Write failing test first**

File: `src/test/java/cl/timbre/alert/FolioAlertServiceTest.java`

```java
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

        when(properties.getCron()).thenReturn("0 6 * * *");
        when(properties.isEnabled()).thenReturn(true);

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FolioAlertServiceTest -v`

Expected: Test fails with "cannot find symbol" (class not created yet).

- [ ] **Step 3: Implement FolioAlertService**

File: `src/main/java/cl/timbre/alert/FolioAlertService.java`

```java
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
import java.time.ZoneId;
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
            log.info("Folio alert sent to {} for emisor {}", String.join(", ", recipients), emisor.getId());
        } catch (Exception e) {
            log.warn("Failed to send folio alert for emisor {}: {}", emisor.getId(), e.getMessage(), e);
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=FolioAlertServiceTest -v`

Expected: All tests pass (2-3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cl/timbre/alert/FolioAlertService.java \
         src/test/java/cl/timbre/alert/FolioAlertServiceTest.java
git commit -m "feat: create FolioAlertService for email composition and sending (phase E)"
```

---

### Task 6: Create FolioAlertJob (Orchestration Logic)

**Files:**
- Create: `src/main/java/cl/timbre/alert/FolioAlertJob.java`
- Test: `src/test/java/cl/timbre/alert/FolioAlertJobTest.java`

**Interfaces:**
- Consumes: EmisorRepository, FolioAssigner, FolioAlertRepository, FolioAlertService
- Produces: FolioAlertJob with method:
  - `verificarYAlertar() -> void` (public)

- [ ] **Step 1: Write failing test**

File: `src/test/java/cl/timbre/alert/FolioAlertJobTest.java`

```java
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
        when(folioAlertRepository.findByEmisorIdAndTipoDte("76123456", 33))
                .thenReturn(Optional.of(recentAlert));  // recent alert exists

        job.verificarYAlertar();

        verify(folioAlertService, never()).enviarAlerta(any(), anyInt(), anyMap());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FolioAlertJobTest -v`

Expected: Test fails (class not created).

- [ ] **Step 3: Implement FolioAlertJob**

File: `src/main/java/cl/timbre/alert/FolioAlertJob.java`

```java
package cl.timbre.alert;

import cl.timbre.caf.FolioAssigner;
import cl.timbre.domain.Emisor;
import cl.timbre.domain.FolioAlert;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.repository.FolioAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class FolioAlertJob {

    private static final Logger log = LoggerFactory.getLogger(FolioAlertJob.class);
    private static final int[] TIPOS_SOPORTADOS = {33, 61};
    private static final long ALERTA_DELAY_MS = 24 * 60 * 60 * 1000;  // 24 hours

    private final EmisorRepository emisorRepository;
    private final FolioAssigner folioAssigner;
    private final FolioAlertRepository folioAlertRepository;
    private final FolioAlertService folioAlertService;

    @Value("${timbre.folio-alert-threshold:20}")
    private int threshold;

    public FolioAlertJob(EmisorRepository emisorRepository, FolioAssigner folioAssigner,
                        FolioAlertRepository folioAlertRepository, FolioAlertService folioAlertService) {
        this.emisorRepository = emisorRepository;
        this.folioAssigner = folioAssigner;
        this.folioAlertRepository = folioAlertRepository;
        this.folioAlertService = folioAlertService;
    }

    public void verificarYAlertar() {
        List<Emisor> emisores = emisorRepository.findAll();
        int alertasEnviadas = 0;

        for (Emisor emisor : emisores) {
            Map<Integer, Integer> foliosPorTipo = new HashMap<>();
            Integer tipoDteConAlerta = null;

            for (int tipoDte : TIPOS_SOPORTADOS) {
                try {
                    int disponibles = folioAssigner.disponibles(emisor.getId(), tipoDte);
                    foliosPorTipo.put(tipoDte, disponibles);

                    if (disponibles < threshold) {
                        if (tipoDteConAlerta == null) {
                            tipoDteConAlerta = tipoDte;  // Remember first tipo that triggered
                        }
                    }
                } catch (Exception e) {
                    log.error("Error checking folios for emisor {} tipo {}: {}", emisor.getId(), tipoDte, e.getMessage());
                }
            }

            if (tipoDteConAlerta != null) {
                Optional<FolioAlert> lastAlert = folioAlertRepository.findByEmisorIdAndTipoDte(
                        emisor.getId(), tipoDteConAlerta);

                boolean shouldSendAlert = lastAlert.isEmpty() ||
                        lastAlert.get().getLastAlertSentAt().toEpochMilli() < System.currentTimeMillis() - ALERTA_DELAY_MS;

                if (shouldSendAlert) {
                    folioAlertService.enviarAlerta(emisor, tipoDteConAlerta, foliosPorTipo);

                    FolioAlert alert = lastAlert.orElseGet(() -> FolioAlert.builder()
                            .id(java.util.UUID.randomUUID().toString())
                            .emisorId(emisor.getId())
                            .tipoDte(tipoDteConAlerta)
                            .build());
                    alert.setLastAlertSentAt(Instant.now());
                    alert.setUpdatedAt(Instant.now());
                    folioAlertRepository.save(alert);
                    alertasEnviadas++;
                } else {
                    log.debug("Alert already sent for emisor {} tipo {} within 24h, skipping", 
                            emisor.getId(), tipoDteConAlerta);
                }
            }
        }

        log.info("Folio alert job completed. Checked {} emisores, sent {} alerts", emisores.size(), alertasEnviadas);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=FolioAlertJobTest -v`

Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cl/timbre/alert/FolioAlertJob.java \
         src/test/java/cl/timbre/alert/FolioAlertJobTest.java
git commit -m "feat: create FolioAlertJob for orchestration logic (phase E)"
```

---

### Task 7: Create FolioAlertJobScheduler (Scheduled Trigger)

**Files:**
- Create: `src/main/java/cl/timbre/alert/FolioAlertJobScheduler.java`

**Interfaces:**
- Consumes: FolioAlertJob, FolioAlertProperties
- Produces: FolioAlertJobScheduler with @Scheduled method triggered daily via cron

- [ ] **Step 1: Create scheduler component**

File: `src/main/java/cl/timbre/alert/FolioAlertJobScheduler.java`

```java
package cl.timbre.alert;

import cl.timbre.config.FolioAlertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FolioAlertJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(FolioAlertJobScheduler.class);

    private final FolioAlertJob folioAlertJob;
    private final FolioAlertProperties properties;

    public FolioAlertJobScheduler(FolioAlertJob folioAlertJob, FolioAlertProperties properties) {
        this.folioAlertJob = folioAlertJob;
        this.properties = properties;
    }

    @Scheduled(cron = "${timbre.folio-alert.cron:0 6 * * *}")
    public void runAlert() {
        if (!properties.isEnabled()) {
            log.debug("Folio alert job disabled, skipping");
            return;
        }

        log.info("Starting folio alert job");
        try {
            folioAlertJob.verificarYAlertar();
        } catch (Exception e) {
            log.error("Folio alert job failed: {}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 2: Verify no syntax errors**

Run: `grep -n "class FolioAlertJobScheduler" src/main/java/cl/timbre/alert/FolioAlertJobScheduler.java`

Expected: Class definition found.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/cl/timbre/alert/FolioAlertJobScheduler.java
git commit -m "feat: create FolioAlertJobScheduler with cron trigger (phase E)"
```

---

### Task 8: Add Configuration to application.yml

**Files:**
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: FolioAlertProperties config expectations
- Produces: Config entries for folio-alert (enabled, cron) in application.yml

- [ ] **Step 1: Read current application.yml**

Run: `Read src/main/resources/application.yml`

Note the structure (timbre block, storage block, etc.).

- [ ] **Step 2: Add folio-alert config block to timbre section**

After the existing `timbre` entries (admin-email, mail-from, folio-alert-threshold), add:

```yaml
timbre:
  folio-alert-threshold: ${FOLIO_ALERT_THRESHOLD:20}
  admin-email: ${TIMBRE_ADMIN_EMAIL:}
  mail-from: ${TIMBRE_MAIL_FROM:}
  folio-alert:
    enabled: ${FOLIO_ALERT_ENABLED:true}
    cron: ${FOLIO_ALERT_CRON:0 6 * * *}
```

(Ensure proper YAML indentation — 2 spaces per level)

- [ ] **Step 3: Verify application.yml syntax**

Run: `mvn clean compile -q 2>&1 | grep -i "yaml\|parse\|error" || echo "YAML syntax OK"`

Expected: No YAML parsing errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "feat: add folio-alert configuration to application.yml (phase E)"
```

---

### Task 9: Create Integration Test

**Files:**
- Create: `src/test/java/cl/timbre/alert/FolioAlertIntegrationTest.java`

**Interfaces:**
- Consumes: Real database (via @SpringBootTest), EmisorRepository, FolioAlertRepository, FolioAssigner
- Produces: Integration test verifying end-to-end behavior

- [ ] **Step 1: Create integration test**

File: `src/test/java/cl/timbre/alert/FolioAlertIntegrationTest.java`

```java
package cl.timbre.alert;

import cl.timbre.domain.Emisor;
import cl.timbre.domain.FolioAlert;
import cl.timbre.domain.FolioRange;
import cl.timbre.repository.EmisorRepository;
import cl.timbre.repository.FolioAlertRepository;
import cl.timbre.repository.FolioRangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class FolioAlertIntegrationTest {

    @Autowired
    private EmisorRepository emisorRepository;

    @Autowired
    private FolioRangeRepository folioRangeRepository;

    @Autowired
    private FolioAlertRepository folioAlertRepository;

    @Autowired
    private FolioAlertJob folioAlertJob;

    @BeforeEach
    void setUp() {
        folioAlertRepository.deleteAll();
        folioRangeRepository.deleteAll();
        emisorRepository.deleteAll();
    }

    @Test
    void folioAlertJob_creates_alert_record_when_folios_below_threshold() {
        // Setup: create emisor with few folios (15 available, below default threshold of 20)
        Emisor emisor = new Emisor();
        emisor.setId("76123456");
        emisor.setRutSinDv("76123456");
        emisor.setRazonSocial("Test Company");
        emisor.setEmail("test@example.com");
        emisor.setRepresentanteRut("12345678");
        emisorRepository.save(emisor);

        FolioRange range = new FolioRange();
        range.setEmisorId("76123456");
        range.setTipoDte(33);
        range.setFolioDesde(1000);
        range.setFolioHasta(1020);
        range.setFolioActual(1005);  // 15 folios available (1020 - 1005)
        range.setAgotado(false);
        folioRangeRepository.save(range);

        // Execute
        folioAlertJob.verificarYAlertar();

        // Verify: FolioAlert record created
        Optional<FolioAlert> alert = folioAlertRepository.findByEmisorIdAndTipoDte("76123456", 33);
        assertThat(alert).isPresent();
        assertThat(alert.get().getLastAlertSentAt()).isNotNull();
    }

    @Test
    void folioAlertJob_skips_duplicate_alert_within_24h() {
        Emisor emisor = new Emisor();
        emisor.setId("76123456");
        emisor.setRutSinDv("76123456");
        emisor.setRazonSocial("Test Company");
        emisor.setEmail("test@example.com");
        emisor.setRepresentanteRut("12345678");
        emisorRepository.save(emisor);

        FolioRange range = new FolioRange();
        range.setEmisorId("76123456");
        range.setTipoDte(33);
        range.setFolioDesde(1000);
        range.setFolioHasta(1015);
        range.setFolioActual(1005);  // 10 folios available
        range.setAgotado(false);
        folioRangeRepository.save(range);

        // Create existing alert (sent 1 hour ago)
        FolioAlert existingAlert = new FolioAlert();
        existingAlert.setId("alert-1");
        existingAlert.setEmisorId("76123456");
        existingAlert.setTipoDte(33);
        existingAlert.setLastAlertSentAt(Instant.now().minusSeconds(3600));
        existingAlert.setCreatedAt(Instant.now().minusSeconds(3600));
        existingAlert.setUpdatedAt(Instant.now().minusSeconds(3600));
        folioAlertRepository.save(existingAlert);

        Instant alertSentBefore = existingAlert.getLastAlertSentAt();

        // Execute job
        folioAlertJob.verificarYAlertar();

        // Verify: alert was NOT updated (still same timestamp)
        Optional<FolioAlert> alertAfter = folioAlertRepository.findByEmisorIdAndTipoDte("76123456", 33);
        assertThat(alertAfter).isPresent();
        assertThat(alertAfter.get().getLastAlertSentAt()).isEqualTo(alertSentBefore);
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `mvn test -Dtest=FolioAlertIntegrationTest -v`

Expected: All tests pass (requires test profile with H2 or test DB configured).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/cl/timbre/alert/FolioAlertIntegrationTest.java
git commit -m "test: add FolioAlertIntegrationTest for E2E verification (phase E)"
```

---

### Task 10: Run Full Test Suite and Verify

**Files:**
- None (verification only)

**Interfaces:**
- Consumes: All tasks 1-9
- Produces: Verified build with all Phase E tests passing

- [ ] **Step 1: Run all Phase E tests**

Run: `mvn test -Dtest=FolioAlert* -v`

Expected: All tests pass (FolioAlertServiceTest, FolioAlertJobTest, FolioAlertIntegrationTest = ~6-8 tests total).

- [ ] **Step 2: Run full project test suite**

Run: `mvn clean test -v`

Expected: All tests pass (49 Phase D tests + Phase E tests).

- [ ] **Step 3: Verify Flyway migration is valid**

Run: `mvn compile -q && echo "Build successful"`

Expected: Compilation succeeds (Flyway migrations validated at startup).

- [ ] **Step 4: Quick manual verification (optional)**

Start app locally:
```bash
mvn spring-boot:run -DskipTests
```

Monitor logs for:
```
Starting folio alert job
Folio alert job completed. Checked N emisores, sent M alerts
```

Stop with Ctrl+C.

- [ ] **Step 5: Final commit**

No code changes, but verify git status clean:

```bash
git status
```

Expected: nothing to commit, working tree clean.

---

## Summary

**10 tasks total:**

| Task | Component | Status |
|------|-----------|--------|
| 1 | Emisor.email + FolioAlert table migration | ✅ |
| 2 | FolioAlert entity | ✅ |
| 3 | FolioAlertRepository | ✅ |
| 4 | FolioAlertProperties config | ✅ |
| 5 | FolioAlertService (email) | ✅ |
| 6 | FolioAlertJob (orchestration) | ✅ |
| 7 | FolioAlertJobScheduler (cron) | ✅ |
| 8 | application.yml configuration | ✅ |
| 9 | Integration test (E2E) | ✅ |
| 10 | Full suite verification | ✅ |

**Test coverage:**
- FolioAlertServiceTest: 3 tests (email composition, recipients, error handling)
- FolioAlertJobTest: 2 tests (alert send, deduplication)
- FolioAlertIntegrationTest: 2 tests (E2E with DB, duplicate prevention)
- Total Phase E: ~7 tests

**No new external dependencies required.** All components integrate cleanly with existing architecture (FolioAssigner, repositories, Spring Mail, Scheduler).
