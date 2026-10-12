package it.gerio.platform.order;

import static it.gerio.platform.tenant.TestShops.SHOP_A;
import static it.gerio.platform.tenant.TestShops.SHOP_B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.TenantContext;
import it.gerio.platform.tenant.TenantStatus;
import it.gerio.platform.tenant.TestShops;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lookups used by the Stripe webhook and the outcome page find only orders of the shop in the
 * context, even with the session id or the order id of another shop.
 */
@QuarkusTest
class OrderLookupIsolationTest {

  private static final CurrentTenant.Shop A =
      new CurrentTenant.Shop(SHOP_A, "Fioraia A", ZoneId.of("Europe/Rome"));

  @Inject DataSource dataSource;
  @Inject TenantContext tenantContext;
  @Inject OrderLookup lookup;

  private TestShops shops;
  private long orderA;
  private long orderB;

  @BeforeEach
  void twoShopsWithOneOrderEach() throws SQLException {
    shops = new TestShops(dataSource);
    shops.insertTenant(SHOP_A, "fioraia-a", "Fioraia A", TenantStatus.ACTIVE);
    shops.insertTenant(SHOP_B, "fioraia-b", "Fioraia B", TenantStatus.ACTIVE);
    orderA = insertOrder(SHOP_A, "token-a", "cs_test_a");
    orderB = insertOrder(SHOP_B, "token-b", "cs_test_b");
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("delete from customer_order");
    }
    shops.deleteAll();
  }

  @Test
  void aSessionIdIsFoundOnlyInItsOwnShop() {
    tenantContext.set(A);

    assertEquals(orderA, (long) inTransaction(() -> lookup.bySessionId("cs_test_a").get().getId()));
    assertTrue(inTransaction(() -> lookup.bySessionId("cs_test_b")).isEmpty());
    assertTrue(inTransaction(() -> lookup.outcome("cs_test_b", null)).isEmpty());
    assertTrue(inTransaction(() -> lookup.outcome("cs_test_a", null)).isPresent());
  }

  @Test
  void aClientReferenceIsFoundOnlyInItsOwnShop() {
    tenantContext.set(A);

    assertTrue(inTransaction(() -> lookup.lockedByReference(Long.toString(orderA))).isPresent());
    assertTrue(inTransaction(() -> lookup.lockedByReference(Long.toString(orderB))).isEmpty());
    assertTrue(inTransaction(() -> lookup.lockedByReference("abc")).isEmpty());
    assertTrue(inTransaction(() -> lookup.lockedByReference(null)).isEmpty());
  }

  private long insertOrder(String tenantId, String token, String session) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "insert into customer_order (tenant_id, number, public_token, status,"
                    + " fulfillment_type, customer_name, customer_email, delivery_date,"
                    + " subtotal_cents, delivery_fee_cents, total_cents, vat_breakdown,"
                    + " terms_version, stripe_checkout_session_id) values (?, '2026-0001', ?,"
                    + " 'PENDING_PAYMENT', 'PICKUP', 'Cliente', 'cliente@example.test',"
                    + " date '2026-10-13', 1000, 0, 1000, '[]', 'test', ?) returning id")) {
      insert.setString(1, tenantId);
      insert.setString(2, token);
      insert.setString(3, session);
      try (ResultSet rows = insert.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private static <T> T inTransaction(Supplier<T> work) {
    return QuarkusTransaction.requiringNew().call(work::get);
  }
}
