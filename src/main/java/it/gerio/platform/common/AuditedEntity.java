package it.gerio.platform.common;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Columns every mutable table carries (ADR-0003): {@code created_at}, {@code updated_at} and {@code
 * version}.
 *
 * <p>The timestamps come from {@link AuditListener}, which reads the injected {@link
 * java.time.Clock} instead of the JVM clock, so tests can fix the time. {@code version} backs JPA
 * optimistic locking: two edits of the same row in parallel make the second one fail with an {@code
 * OptimisticLockException} instead of silently overwriting the first.
 *
 * <p>The fields are private with getters only: application code must never set them by hand.
 *
 * <p><b>Change audited rows through loaded entities.</b> A bulk HQL update (Panache's {@code
 * Entity.update("...")}) bypasses both the listener and the version check: {@code updated_at} keeps
 * its old value and a concurrent edit no longer fails. If a bulk update is really needed, use
 * {@code update versioned ...} and set {@code updatedAt} from the Clock ({@code
 * AuditedEntityTest}).
 */
@MappedSuperclass
@EntityListeners(AuditListener.class)
public abstract class AuditedEntity extends PanacheEntityBase {

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }

  // Package-private: only AuditListener, in this package, may set the timestamps.
  void markCreated(Instant now) {
    createdAt = now;
    updatedAt = now;
  }

  void markUpdated(Instant now) {
    updatedAt = now;
  }
}
