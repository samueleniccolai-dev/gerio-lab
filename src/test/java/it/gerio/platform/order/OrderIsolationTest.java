package it.gerio.platform.order;

import static it.gerio.platform.tenant.TestShops.SHOP_A;
import static it.gerio.platform.tenant.TestShops.SHOP_B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Orders, their lines and their events are per shop: shop A does not see or change those of shop B,
 * not even with B's id. Rows are prepared and checked with plain JDBC, which the filter does not
 * touch. The order number restarts for every shop.
 */
@QuarkusTest
class OrderIsolationTest {

  private static final CurrentTenant.Shop A =
      new CurrentTenant.Shop(SHOP_A, "Fioraia A", ZoneId.of("Europe/Rome"));

  @Inject DataSource dataSource;
  @Inject TenantContext tenantContext;
  @Inject OrderNumbers numbers;

  private TestShops shops;
  private long orderA;
  private long orderB;

  @BeforeEach
  void twoShopsWithOneOrderEach() throws SQLException {
    shops = new TestShops(dataSource);
    shops.insertTenant(SHOP_A, "fioraia-a", "Fioraia A", TenantStatus.ACTIVE);
    shops.insertTenant(SHOP_B, "fioraia-b", "Fioraia B", TenantStatus.ACTIVE);
    orderA = insertOrder(SHOP_A, "token-a");
    orderB = insertOrder(SHOP_B, "token-b");
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("delete from order_event");
      statement.execute("delete from order_item");
      statement.execute("delete from customer_order");
      statement.execute("delete from tenant_counter");
    }
    shops.deleteAll();
  }

  @Test
  void aShopListsAndLoadsOnlyItsOwnOrders() {
    tenantContext.set(A);

    List<Long> ids =
        inTransaction(
            () -> CustomerOrder.<CustomerOrder>listAll().stream().map(o -> o.getId()).toList());

    assertEquals(List.of(orderA), ids);
    assertNull(inTransaction(() -> CustomerOrder.findById(orderB)));
    assertEquals(1L, inTransaction(() -> OrderItem.count()));
    assertEquals(1L, inTransaction(() -> OrderEvent.count()));
    assertEquals(0L, inTransaction(() -> OrderItem.count("orderId", orderB)));
    assertEquals(0L, inTransaction(() -> OrderEvent.count("orderId", orderB)));
  }

  @Test
  void aShopCannotChangeOrDeleteTheOrdersOfAnother() throws SQLException {
    tenantContext.set(A);

    int updated =
        inTransaction(() -> CustomerOrder.update("customerName = 'rubato' where id = ?1", orderB));
    long deleted = inTransaction(() -> CustomerOrder.delete("id", orderB));

    assertEquals(0, updated);
    assertEquals(0L, deleted);
    assertEquals("Cliente b", customerName(orderB));
  }

  @Test
  void orderNumbersAreCountedPerShop() {
    tenantContext.set(A); // the session needs some tenant; the counter takes the shop id as given
    assertEquals("2026-0001", inTransaction(() -> numbers.next(SHOP_A, 2026)));
    assertEquals("2026-0002", inTransaction(() -> numbers.next(SHOP_A, 2026)));
    assertEquals("2026-0001", inTransaction(() -> numbers.next(SHOP_B, 2026)));
    assertEquals("2027-0001", inTransaction(() -> numbers.next(SHOP_A, 2027)));
  }

  // --- helpers --------------------------------------------------------------------------------

  private long insertOrder(String tenantId, String token) throws SQLException {
    long id;
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "insert into customer_order (tenant_id, number, public_token, status,"
                    + " fulfillment_type, customer_name, customer_email, delivery_date,"
                    + " subtotal_cents, delivery_fee_cents, total_cents, vat_breakdown,"
                    + " terms_version) values (?, '2026-0001', ?, 'PENDING_PAYMENT', 'PICKUP',"
                    + " ?, 'cliente@example.test', date '2026-10-13', 1000, 0, 1000, '[]',"
                    + " 'test') returning id")) {
      insert.setString(1, tenantId);
      insert.setString(2, token);
      insert.setString(3, tenantId.equals(SHOP_A) ? "Cliente a" : "Cliente b");
      try (ResultSet rows = insert.executeQuery()) {
        rows.next();
        id = rows.getLong(1);
      }
    }
    update(
        "insert into order_item (tenant_id, order_id, name_snapshot, unit_price_cents, quantity,"
            + " vat_rate_bp, line_total_cents) values (?, ?, 'Rose', 1000, 1, 1000, 1000)",
        tenantId,
        id);
    update(
        "insert into order_event (tenant_id, order_id, to_status) values (?, ?, 'PENDING_PAYMENT')",
        tenantId,
        id);
    return id;
  }

  private String customerName(long id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement("select customer_name from customer_order where id = ?")) {
      query.setLong(1, id);
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getString(1);
      }
    }
  }

  private void update(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private static <T> T inTransaction(Supplier<T> work) {
    return QuarkusTransaction.requiringNew().call(work::get);
  }
}
