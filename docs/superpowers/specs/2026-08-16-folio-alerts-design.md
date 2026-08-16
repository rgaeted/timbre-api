# Phase E: Email Alerts for Low Folios

> **Overview:** Implement a daily scheduled job that monitors folio availability per emisor and sends email alerts when available folios fall below a configurable threshold, with daily deduplication to avoid alert spam.

## Goal

Enable proactive notifications to emisores and administrators when folio supply runs low, ensuring timely CAF uploads and uninterrupted document issuance.

## Architecture

### Core Components

**FolioAlertJob** (@Component)
- Orchestrates the daily alert check
- Iterates all emisores, queries FolioAssigner.disponibles() for each tipo de DTE
- Compares against threshold, decides whether to send alert
- Delegates email composition to FolioAlertService
- Logs summary of alerts sent

**FolioAlertJobScheduler** (@Component)
- Wraps FolioAlertJob with @Scheduled(cron)
- Configurable cron expression (default: daily at 6 AM)
- Honors enabled flag from config (timbre.folio-alert.enabled)

**FolioAlertService**
- Composes email HTML from template
- Invokes Spring Mail RestTemplate to send
- Handles mail failures gracefully (log warning, don't block job)

**FolioAlert** Entity
- Tracks last alert sent per emisor + tipo de DTE
- Used to enforce 24-hour deduplication window

### Data Flow

```
FolioAlertJobScheduler.run()
  └─> FolioAlertJob.verificarYAlertar()
      └─> For each Emisor:
          └─> For each tipoDte in [33, 61]:
              ├─> foliosDisponibles = FolioAssigner.disponibles(emisorId, tipoDte)
              ├─> If foliosDisponibles < threshold:
              │   ├─> lastAlert = FolioAlertRepository.findByEmisorIdAndTipoDte()
              │   ├─> If no recent alert (>24h ago or never sent):
              │   │   ├─> FolioAlertService.enviarAlerta(emisor, tipoDte, disponibles)
              │   │   │   ├─> Query all tipoDte for this emisor (to include in email)
              │   │   │   ├─> Render HTML template
              │   │   │   ├─> Send to admin-email + emisor.email
              │   │   │   └─> Log success/failure
              │   │   ├─> FolioAlertRepository.save/update(emisorId, tipoDte, now)
              │   │   └─> Log "Alert sent to {emisor}/{tipoDte}"
              │   └─> Else: Skip (alert already sent today)
              └─> Else: Log "Folio ok for {emisor}/{tipoDte}"
      └─> Log summary: "Checked N emisores, M alerts sent"
```

## Database Schema

### New Table: `folio_alert`

```sql
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

### Modified Table: `emisor`

Add column (nullable — not all emisores may want alerts):
```sql
ALTER TABLE emisor ADD COLUMN email VARCHAR(255);
```

## Configuration

Existing blocks in `application.yml` (no changes needed):

```yaml
spring:
  mail:
    host: ${MAIL_HOST:}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true

timbre:
  folio-alert-threshold: ${FOLIO_ALERT_THRESHOLD:20}
  admin-email: ${TIMBRE_ADMIN_EMAIL:}
  mail-from: ${TIMBRE_MAIL_FROM:}
```

New config block (add to `application.yml`):
```yaml
timbre:
  folio-alert:
    enabled: ${FOLIO_ALERT_ENABLED:true}
    cron: ${FOLIO_ALERT_CRON:0 6 * * *}  # Daily at 6 AM UTC
```

### ConfigurationProperties

```java
@ConfigurationProperties(prefix = "timbre.folio-alert")
public class FolioAlertProperties {
    private boolean enabled = true;
    private String cron = "0 6 * * *";  // 6 AM UTC daily
    // getters/setters
}
```

## Email Template

**Subject:** `[Timbre] Alerta: Folios bajos para {Emisor Name}`

**HTML Body:**
```html
<p>Hola,</p>

<p>Los folios disponibles para <strong>{Emisor Name}</strong> están bajo el threshold 
configurado ({THRESHOLD} folios mínimos).</p>

<p><strong>Detalles por tipo de DTE:</strong></p>
<ul>
  <li>Factura (33): {FOLIOS_33} folios disponibles</li>
  <li>Nota de Crédito (61): {FOLIOS_61} folios disponibles</li>
</ul>

<p>Por favor, sube un CAF nuevo desde el SII para continuar emitiendo.</p>

<hr>
<p><small>Timbre API | {TIMESTAMP_UTC}</small></p>
```

## Error Handling

**Mail send failure:** Log warning, continue job (non-blocking). Email delivery is not critical to system stability.

**Database errors (FolioAlertRepository):** Log error, skip that emisor/tipoDte, continue with next.

**FolioAssigner.disponibles() failure:** Log error, skip that emisor, continue.

**Null email on Emisor:** Alert sent only to admin-email (if configured). If admin-email is null, alert is skipped entirely with log warning.

## Alert Deduplication Logic

An alert is sent if:
1. Available folios for a tipo de DTE < threshold, AND
2. Either:
   - No FolioAlert record exists for this (emisor, tipoDte) pair, OR
   - The existing record's `last_alert_sent_at` is > 24 hours ago

Update logic:
- On successful send: `UPDATE folio_alert SET last_alert_sent_at = now(), updated_at = now()` (upsert)
- Do NOT update if mail send fails (allow retry next run)

## Testing

**Unit Tests:**

- `FolioAlertJobTest`: Mock FolioAssigner, Repository, Mail. Verify:
  - Alerts sent only when below threshold
  - Alerts not repeated within 24h
  - Summary logged correctly
  - Null email handled gracefully

- `FolioAlertServiceTest`: Verify:
  - HTML template renders correctly with actual data
  - Recipients correct (admin + emisor.email)
  - Mail service invoked with correct subject/body
  - Mail send failures logged but don't throw

- `FolioAlertRepositoryTest`: Verify:
  - Insert new FolioAlert record
  - Find by emisor + tipoDte
  - Update last_alert_sent_at (upsert semantics)

**Integration Tests:**

- `FolioAlertJobIntegrationTest`: @SpringBootTest with real DB:
  - Create test emisores with various folio counts
  - Trigger job, verify alerts sent to correct recipients
  - Verify FolioAlert records created
  - Verify 24h deduplication works (second run same day sends nothing)
  - Verify daily alert resent after 24h window passes

## Dependencies

Already present in `pom.xml`:
- `spring-boot-starter-mail`

No new dependencies required.

## Implementation Order

1. Add `email` column to `Emisor` entity and migration
2. Create `FolioAlert` entity and migration
3. Create `FolioAlertRepository` with query methods
4. Create `FolioAlertProperties` config class
5. Create `FolioAlertService` (email composition and send)
6. Create `FolioAlertJob` (orchestration logic)
7. Create `FolioAlertJobScheduler` (scheduled trigger)
8. Unit tests for each component
9. Integration test (E2E with DB)
10. Add configuration to `application.yml`

## Backwards Compatibility

- Existing emisores without email address: alerts still sent to admin-email only
- If mail service is unavailable: job logs warning but continues (non-blocking)
- Folios can continue to be issued even if alerts fail to send
- FolioAlertProperties has sensible defaults (enabled=true, cron=6 AM UTC)

## Success Criteria

1. ✅ FolioAlert table created, migrations in place
2. ✅ Emisor.email column added (nullable)
3. ✅ Job runs daily at configured time
4. ✅ Email sent when folios < threshold, NOT sent if already sent <24h ago
5. ✅ Email includes admin + emisor recipients
6. ✅ Email body includes per-tipoDte folio counts
7. ✅ All unit tests pass
8. ✅ Integration test passes (real DB, mock mail)
9. ✅ Manual verification: emit documents, watch folio count drop, receive alert on day 1, no duplicate alert on day 2 (within 24h), alert resent after 24h passes
