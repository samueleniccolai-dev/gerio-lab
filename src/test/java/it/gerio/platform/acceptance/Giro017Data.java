package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Giro 017 test data (docs/lab/giri/017-piano.md): the data of giro 015 plus plain-JDBC reads and
 * writes of {@code delivery_zone} and of {@code tenant.settings}. Orders and zones are emptied
 * (when the tables exist) before the shops. A missing V8 table or column makes a test fail with an
 * assertion, not with an SQL error.
 */
final class Giro017Data {

  static final String LUCCA = "Lucca centro";
  static final String CAPANNORI = "Capannori e Porcari";
  static final String VERSILIA = "Versilia";
  static final String MILANO = "Milano centro";

  private final DataSource dataSource;
  final Giro015Data giro015;

  Giro017Data(DataSource dataSource) throws IOException, SQLException {
    this.dataSource = dataSource;
    deleteOrdersAndZones();
    giro015 = new Giro015Data(dataSource);
  }

  Giro010CheckoutData checkout() {
    return giro015.giro014.giro011.checkout;
  }

  void deleteAll() throws SQLException {
    deleteOrdersAndZones();
    giro015.deleteAll();
  }

  /** Outbox, orders, then zones: customer_order.zone_id points to delivery_zone. */
  private void deleteOrdersAndZones() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "do $$ begin if to_regclass('outbox_message') is not null then"
              + " execute 'delete from outbox_message'; end if; end $$");
    }
    Giro010CheckoutData.deleteOrders(dataSource);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "do $$ begin if to_regclass('delivery_zone') is not null then"
              + " execute 'delete from delivery_zone'; end if; end $$");
    }
  }

  // --- delivery_zone --------------------------------------------------------------------------

  void requireZones() throws SQLException {
    assertTrue(
        bool("select to_regclass('delivery_zone') is not null"),
        "table delivery_zone does not exist (V8 missing)");
  }

  /** Zones of a shop, by id, with the arrays as text. */
  List<Map<String, Object>> zones(String tenantId) throws SQLException {
    requireZones();
    return rows(
        "select id, name, array_to_string(postal_codes, ',') as postal_codes,"
            + " array_to_string(municipalities, ',') as municipalities, fee_cents,"
            + " min_order_cents, active from delivery_zone where tenant_id = ? order by id",
        tenantId);
  }

  long zoneId(String tenantId, String name) throws SQLException {
    for (Map<String, Object> zone : zones(tenantId)) {
      if (name.equals(zone.get("name"))) {
        return ((Number) zone.get("id")).longValue();
      }
    }
    throw new AssertionError("no zone «" + name + "» for " + tenantId);
  }

  /** Inserts a zone with plain JDBC and returns its id. */
  long insertZone(
      String tenantId,
      String name,
      String postalCode,
      String municipality,
      long feeCents,
      long minOrderCents)
      throws SQLException {
    requireZones();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "insert into delivery_zone (tenant_id, name, postal_codes, municipalities,"
                    + " fee_cents, min_order_cents, active)"
                    + " values (?, ?, array[?]::text[], array[?]::text[], ?, ?, true)"
                    + " returning id")) {
      insert.setString(1, tenantId);
      insert.setString(2, name);
      insert.setString(3, postalCode);
      insert.setString(4, municipality);
      insert.setLong(5, feeCents);
      insert.setLong(6, minOrderCents);
      try (ResultSet rows = insert.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  void setZoneFee(long zoneId, long feeCents) throws SQLException {
    requireZones();
    update("update delivery_zone set fee_cents = ? where id = ?", feeCents, zoneId);
  }

  void setZoneActive(long zoneId, boolean active) throws SQLException {
    requireZones();
    update("update delivery_zone set active = ? where id = ?", active, zoneId);
  }

  // --- tenant.settings ------------------------------------------------------------------------

  String setting(String tenantId, String key) throws SQLException {
    List<Map<String, Object>> found =
        rows("select settings ->> ? as value from tenant where id = ?", key, tenantId);
    assertEquals(1, found.size(), "tenant " + tenantId);
    return (String) found.get(0).get("value");
  }

  void setSettings(String tenantId, String json) throws SQLException {
    update("update tenant set settings = ?::jsonb where id = ?", json, tenantId);
  }

  // --- orders ---------------------------------------------------------------------------------

  List<Map<String, Object>> orders(String tenantId) throws SQLException {
    return checkout().orders(tenantId);
  }

  long countOrders() throws SQLException {
    return checkout().countOrders();
  }

  /** The only order of the demo. */
  Map<String, Object> onlyDemoOrder() throws SQLException {
    List<Map<String, Object>> orders = orders(DEMO_ID);
    assertEquals(1, orders.size(), "orders of the demo: " + orders);
    return orders.get(0);
  }

  /** A column added by V8: it must exist on the row. */
  static Object column(Map<String, Object> order, String column) {
    assertTrue(order.containsKey(column), "customer_order has no column " + column + " (V8)");
    return order.get(column);
  }

  static long cents(Map<String, Object> row, String column) {
    Object value = row.get(column);
    assertNotNull(value, column + " is null");
    return ((Number) value).longValue();
  }

  /** Sum of taxable_cents + tax_cents over the vat_breakdown of the order. */
  long vatGross(long orderId) throws SQLException {
    List<Map<String, Object>> found =
        rows(
            "select coalesce(sum((e ->> 'taxable_cents')::bigint + (e ->> 'tax_cents')::bigint), 0)"
                + " as gross from customer_order o, jsonb_array_elements(o.vat_breakdown) e"
                + " where o.id = ?",
            orderId);
    return ((Number) found.get(0).get("gross")).longValue();
  }

  /** The distinct rates of the vat_breakdown of the order. */
  List<Object> vatRates(long orderId) throws SQLException {
    return rows(
            "select distinct (e ->> 'rate_bp')::int as rate from customer_order o,"
                + " jsonb_array_elements(o.vat_breakdown) e where o.id = ? order by 1",
            orderId)
        .stream()
        .map(r -> r.get("rate"))
        .toList();
  }

  // --- JDBC -----------------------------------------------------------------------------------

  private boolean bool(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getBoolean(1);
    }
  }

  private List<Map<String, Object>> rows(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query = connection.prepareStatement(sql)) {
      bind(query, parameters);
      try (ResultSet rows = query.executeQuery()) {
        ResultSetMetaData meta = rows.getMetaData();
        List<Map<String, Object>> found = new ArrayList<>();
        while (rows.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i), rows.getObject(i));
          }
          found.add(row);
        }
        return found;
      }
    }
  }

  private void update(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      statement.executeUpdate();
    }
  }

  private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
    for (int i = 0; i < parameters.length; i++) {
      statement.setObject(i + 1, parameters[i]);
    }
  }
}
