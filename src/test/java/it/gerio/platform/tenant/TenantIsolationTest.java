package it.gerio.platform.tenant;

import static it.gerio.platform.tenant.TestShops.SHOP_A;
import static it.gerio.platform.tenant.TestShops.SHOP_B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.hibernate.HibernateException;
import org.hibernate.PropertyValueException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

// PROGETTO.md §5.3: shop A must never read nor change shop B's rows, not even
// with B's ids. Rows are prepared and checked with plain JDBC (TestShops), which
// the tenant filter does not touch. Each test method runs in its own request
// context, so TenantContext starts empty every time.
@QuarkusTest
class TenantIsolationTest {

  private static final CurrentTenant.Shop A =
      new CurrentTenant.Shop(SHOP_A, "Fioraia A", ZoneId.of("Europe/Rome"));

  @Inject DataSource dataSource;
  @Inject TenantContext tenantContext;
  @Inject RequiresNewProbeCounter requiresNewCounter;

  private TestShops shops;
  private long a1;
  private long b1;

  @BeforeEach
  void twoShopsWithTheirRows() throws SQLException {
    shops = new TestShops(dataSource);
    shops.insertTenant(SHOP_A, "fioraia-a", "Fioraia A", TenantStatus.ACTIVE);
    shops.insertTenant(SHOP_B, "fioraia-b", "Fioraia B", TenantStatus.ACTIVE);
    a1 = shops.insertProbe(SHOP_A, "a-1");
    shops.insertProbe(SHOP_A, "a-2");
    b1 = shops.insertProbe(SHOP_B, "b-1");
  }

  @AfterEach
  void cleanUp() throws SQLException {
    shops.deleteAll();
  }

  // --- @TenantId on a @MappedSuperclass (ADR-0003: the Quarkus guide doesn't say) ---

  @Test
  void insertStampsTheShopOfTheSession() throws SQLException {
    tenantContext.set(A);

    ProbeItem saved = inTransaction(() -> persistProbe("nuovo"));

    assertEquals(SHOP_A, saved.getTenantId());
    assertEquals(SHOP_A + "/nuovo", shops.probeRow(saved.getId()));
  }

  // --- A cannot read B ---

  @Test
  void listAllSeesOnlyOwnRows() {
    tenantContext.set(A);

    List<String> names =
        inTransaction(() -> ProbeItem.<ProbeItem>listAll().stream().map(p -> p.name).sorted())
            .toList();

    assertEquals(List.of("a-1", "a-2"), names);
  }

  @Test
  void findByIdWithTheIdOfBFindsNothing() {
    tenantContext.set(A);

    assertNull(inTransaction(() -> ProbeItem.findById(b1)));
  }

  @Test
  void countSeesOnlyOwnRows() {
    tenantContext.set(A);

    long count = inTransaction(() -> ProbeItem.count());

    assertEquals(2L, count);
  }

  // --- A cannot change B ---

  @Test
  void updateAndDeleteByTheIdOfBTouchNothing() throws SQLException {
    tenantContext.set(A);

    int updated = inTransaction(() -> ProbeItem.update("name = 'rubato' where id = ?1", b1));
    long deleted = inTransaction(() -> ProbeItem.delete("id", b1));

    assertEquals(0, updated);
    assertEquals(0L, deleted);
    assertEquals(SHOP_B + "/b-1", shops.probeRow(b1));
  }

  @Test
  void deleteAllDeletesOnlyOwnRows() throws SQLException {
    tenantContext.set(A);

    assertEquals(2L, inTransaction(() -> ProbeItem.deleteAll()));
    assertEquals(SHOP_B + "/b-1", shops.probeRow(b1));
    assertEquals(1L, shops.countAllProbes());
  }

  // The typical PUT bug: a detached object built from the request, with B's id.
  // The filtered load finds no row; since the entity is versioned, Hibernate
  // concludes someone else deleted it, instead of inserting a new row.
  @Test
  void mergingADetachedCopyWithTheIdOfBNeverTouchesB() throws Exception {
    tenantContext.set(A);
    ProbeItem forged = new ProbeItem();
    setField(forged, "id", b1);
    forged.name = "rubato";

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () -> inTransaction(() -> Panache.getEntityManager().merge(forged)));

    findCause(failure, OptimisticLockException.class);
    assertEquals(SHOP_B + "/b-1", shops.probeRow(b1));
    assertEquals(3L, shops.countAllProbes());
  }

  @Test
  void persistRefusesATenantIdOfAnotherShop() throws Exception {
    tenantContext.set(A);
    ProbeItem forged = new ProbeItem();
    forged.name = "intruso";
    setField(forged, "tenantId", SHOP_B);

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                inTransaction(
                    () -> {
                      forged.persist();
                      return forged;
                    }));

    PropertyValueException cause = findCause(failure, PropertyValueException.class);
    assertTrue(cause.getMessage().contains("assigned tenant id differs"), cause.getMessage());
    assertEquals(3L, shops.countAllProbes());
  }

  // No error at all: tenant_id is updatable=false, so Hibernate never writes it
  // on update. The other change (the name) is saved, the owner stays A.
  @Test
  void changingTheTenantIdOfALoadedEntityIsNeverWritten() throws SQLException {
    tenantContext.set(A);

    inTransaction(
        () -> {
          ProbeItem mine = ProbeItem.findById(a1);
          setField(mine, "tenantId", SHOP_B);
          mine.name = "cambiato";
          return mine;
        });

    assertEquals(SHOP_A + "/cambiato", shops.probeRow(a1));
  }

  // --- The platform context is not a "root" tenant ---

  @Test
  void platformContextSeesNoShopRows() {
    tenantContext.set(CurrentTenant.PLATFORM);

    long count = inTransaction(() -> ProbeItem.count());

    assertEquals(0L, count);
  }

  // Not stopped by the tenant filter (inserts are not filtered) but by the foreign
  // key to tenant(id): no tenant can be '__platform__'. Every shop table needs
  // that key, which SchemaRulesTest enforces.
  @Test
  void platformContextCannotInsertShopRows() {
    tenantContext.set(CurrentTenant.PLATFORM);

    RuntimeException failure =
        assertThrows(RuntimeException.class, () -> inTransaction(() -> persistProbe("orfano")));

    PSQLException postgres = findCause(failure, PSQLException.class);
    assertEquals("23503", postgres.getSQLState());
    assertEquals("probe_item_tenant_id_fkey", postgres.getServerErrorMessage().getConstraint());
  }

  // --- Never proceed with an undefined shop ---

  @Test
  void emptyContextFailsInsteadOfGuessing() {
    RuntimeException failure =
        assertThrows(RuntimeException.class, () -> inTransaction(() -> ProbeItem.count()));

    IllegalStateException cause = findCause(failure, IllegalStateException.class);
    assertTrue(cause.getMessage().startsWith("No tenant in context"), cause.getMessage());
  }

  // Quarkus doesn't call our resolver without a request context (e.g. a job that
  // forgot @ActivateRequestContext): Hibernate must refuse the null tenant.
  @Test
  void noRequestContextFailsInsteadOfSeeingEveryShop() throws InterruptedException {
    CompletableFuture<Long> job =
        CompletableFuture.supplyAsync(
            () -> QuarkusTransaction.requiringNew().call(() -> ProbeItem.count()));

    ExecutionException failure = assertThrows(ExecutionException.class, job::get);
    HibernateException cause = findCause(failure, HibernateException.class);
    assertTrue(cause.getMessage().contains("no tenant identifier"), cause.getMessage());
  }

  // --- Native SQL is not filtered: this test documents the danger (§5.3) ---

  @Test
  void nativeSqlSeesEveryShop() {
    tenantContext.set(A);

    Number all =
        inTransaction(
            () ->
                (Number)
                    Panache.getEntityManager()
                        .createNativeQuery("select count(*) from probe_item")
                        .getSingleResult());

    assertEquals(3L, all.longValue());
  }

  // --- §5.2: the tenant is fixed when the session opens ---

  @Test
  void switchingTenantInsideAnOpenTransactionHasNoEffect() {
    tenantContext.set(CurrentTenant.PLATFORM);

    List<Long> counts =
        inTransaction(
            () -> {
              long asPlatform = ProbeItem.count(); // opens the session as the platform
              tenantContext.switchTo(A);
              long sameSession = ProbeItem.count(); // still the platform session
              long newTransaction = requiresNewCounter.count(); // new session, reads A
              return List.of(asPlatform, sameSession, newTransaction);
            });

    assertEquals(List.of(0L, 0L, 2L), counts);
  }

  private static ProbeItem persistProbe(String name) {
    ProbeItem item = new ProbeItem();
    item.name = name;
    item.persist();
    return item;
  }

  private static <T> T inTransaction(Supplier<T> work) {
    return QuarkusTransaction.requiringNew().call(work::get);
  }

  private static void setField(Object target, String name, Object value) {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
        return;
      } catch (NoSuchFieldException notHere) {
        // look in the superclass
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
    throw new IllegalArgumentException("No field " + name);
  }

  private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return type.cast(t);
      }
    }
    return assertInstanceOf(type, failure, "no " + type.getSimpleName() + " in the cause chain");
  }
}
