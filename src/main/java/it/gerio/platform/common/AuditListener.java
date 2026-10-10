package it.gerio.platform.common;

import jakarta.inject.Inject;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Sets the {@link AuditedEntity} timestamps from the injected {@link Clock} (ADR-0003). Quarkus
 * supports CDI injection in JPA entity listeners.
 *
 * <p>Instants are truncated to microseconds, the precision of a PostgreSQL {@code timestamptz}: the
 * value in memory is then exactly the one read back from the database.
 *
 * <p>Only timestamps here: no side effects (email, messages) in the entity lifecycle, those go
 * through the outbox (ADR-0003 point 6).
 */
public class AuditListener {

  @Inject Clock clock;

  @PrePersist
  void onCreate(AuditedEntity entity) {
    entity.markCreated(now());
  }

  // Runs only when Hibernate finds the entity dirty at flush time.
  @PreUpdate
  void onUpdate(AuditedEntity entity) {
    entity.markUpdated(now());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
