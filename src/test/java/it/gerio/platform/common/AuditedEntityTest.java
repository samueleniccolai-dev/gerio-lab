package it.gerio.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.Tenant;
import it.gerio.platform.tenant.TenantContext;
import it.gerio.platform.tenant.TenantDomain;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// ADR-0003: timestamps from the injected Clock, optimistic locking through @Version.
// Tenant and TenantDomain are global tables, so the platform context is enough.
@QuarkusTest
class AuditedEntityTest {

  // Microsecond precision on purpose: that is what a timestamptz stores.
  private static final Instant T0 = Instant.parse("2026-10-01T08:00:00.123456Z");
  private static final Instant T1 = Instant.parse("2026-10-01T09:30:00.654321Z");

  @Inject TenantContext tenantContext;
  @Inject DataSource dataSource;

  private final TestClock clock = new TestClock(T0);

  @BeforeEach
  void setUp() {
    QuarkusMock.installMockForType(clock, Clock.class);
    tenantContext.set(CurrentTenant.PLATFORM);
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("delete from tenant_domain");
      statement.execute("delete from tenant");
    }
  }

  @Test
  void persistTakesBothTimestampsFromTheClock() throws SQLException {
    String id = createTenant("fioraia-a");

    AuditRow row = readAudit("tenant", id);
    assertEquals(T0, row.createdAt());
    assertEquals(T0, row.updatedAt());
    assertEquals(0L, row.version());
  }

  @Test
  void updateMovesUpdatedAtAndVersionButNotCreatedAt() throws SQLException {
    String id = createTenant("fioraia-a");
    clock.set(T1);

    QuarkusTransaction.requiringNew()
        .run(() -> Tenant.<Tenant>findById(id).displayName = "Fioraia rinnovata");

    AuditRow row = readAudit("tenant", id);
    assertEquals(T0, row.createdAt());
    assertEquals(T1, row.updatedAt());
    assertEquals(1L, row.version());
  }

  @Test
  void readingWithoutChangesTouchesNothing() throws SQLException {
    String id = createTenant("fioraia-a");
    clock.set(T1);

    QuarkusTransaction.requiringNew().run(() -> Tenant.findById(id));

    AuditRow row = readAudit("tenant", id);
    assertEquals(T0, row.updatedAt());
    assertEquals(0L, row.version());
  }

  @Test
  void entityBaseGetsItsIdFromTheIdentityColumn() throws SQLException {
    String tenantId = createTenant("fioraia-a");

    Long domainId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  TenantDomain domain = new TenantDomain();
                  domain.tenantId = tenantId;
                  domain.hostname = "fioraia-a.it";
                  domain.persist();
                  return domain.getId();
                });

    assertNotNull(domainId);
    assertEquals(T0, readAudit("tenant_domain", domainId).createdAt());
  }

  // Documents a trap, like the native SQL test in TenantIsolationTest: a bulk HQL
  // update (Panache's Entity.update("...")) goes straight to the database. No
  // entity is loaded, so no listener runs and @Version is not checked nor raised:
  // a colleague's pending edit would no longer detect the conflict.
  @Test
  void bulkUpdateSkipsTheListenerAndTheVersion() throws SQLException {
    String id = createTenant("fioraia-a");
    clock.set(T1);

    int updated =
        QuarkusTransaction.requiringNew()
            .call(() -> Tenant.update("displayName = 'Di massa' where id = ?1", id));

    assertEquals(1, updated);
    AuditRow row = readAudit("tenant", id);
    assertEquals(T0, row.updatedAt());
    assertEquals(0L, row.version());
  }

  // The HQL way out when a bulk update is really needed: "update versioned"
  // raises the version; updatedAt must still be set by hand from the Clock.
  @Test
  void versionedBulkUpdateRaisesTheVersion() throws SQLException {
    String id = createTenant("fioraia-a");
    clock.set(T1);

    QuarkusTransaction.requiringNew()
        .run(
            () ->
                Panache.getEntityManager()
                    .createQuery(
                        "update versioned Tenant set displayName = 'Di massa', updatedAt = :now"
                            + " where id = :id")
                    .setParameter("now", clock.instant())
                    .setParameter("id", id)
                    .executeUpdate());

    AuditRow row = readAudit("tenant", id);
    assertEquals(T1, row.updatedAt());
    assertEquals(1L, row.version());
  }

  @Test
  void twoConflictingEditsFailWithOptimisticLock() {
    String id = createTenant("fioraia-a");
    // Employee 1 opens the edit form: a copy of version 0, detached once its transaction ends.
    Tenant staleCopy = QuarkusTransaction.requiringNew().call(() -> Tenant.findById(id));
    // Employee 2 saves first: the row goes to version 1.
    QuarkusTransaction.requiringNew()
        .run(() -> Tenant.<Tenant>findById(id).displayName = "Salvato per primo");

    // Employee 1 saves the old copy: must fail, not overwrite employee 2's change.
    staleCopy.displayName = "Salvato per secondo";
    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                QuarkusTransaction.requiringNew()
                    .run(() -> Panache.getEntityManager().merge(staleCopy)));

    assertTrue(
        hasCause(failure, OptimisticLockException.class),
        "expected an OptimisticLockException in the cause chain, got: " + failure);
    String saved =
        QuarkusTransaction.requiringNew().call(() -> Tenant.<Tenant>findById(id).displayName);
    assertEquals("Salvato per primo", saved);
  }

  private static String createTenant(String slug) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Tenant tenant =
                  Tenant.create(slug, "Fioraia di prova", "Fioraia di prova Srl", "a@example.test");
              tenant.persist();
              return tenant.getId();
            });
  }

  private record AuditRow(Instant createdAt, Instant updatedAt, long version) {}

  // Plain JDBC: checks what really reached the database, not Hibernate's in-memory copy.
  private AuditRow readAudit(String table, Object id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "select created_at, updated_at, version from " + table + " where id = ?")) {
      query.setObject(1, id);
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next());
        return new AuditRow(
            rows.getObject("created_at", OffsetDateTime.class).toInstant(),
            rows.getObject("updated_at", OffsetDateTime.class).toInstant(),
            rows.getLong("version"));
      }
    }
  }

  private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }
}
