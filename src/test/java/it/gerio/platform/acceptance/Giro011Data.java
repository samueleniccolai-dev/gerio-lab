package it.gerio.platform.acceptance;

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
 * Giro 011 test data: the data of giro 010 plus plain-JDBC reads and writes of {@code stripe_event}
 * and of single orders. {@code stripe_event} is emptied (when it exists) before everything else. A
 * missing V6 table or column makes the test fail with an assertion.
 */
final class Giro011Data {

  private final DataSource dataSource;
  final Giro010CheckoutData checkout;

  Giro011Data(DataSource dataSource) throws IOException, SQLException {
    this.dataSource = dataSource;
    deleteStripeEvents();
    checkout = new Giro010CheckoutData(dataSource);
  }

  void deleteAll() throws SQLException {
    deleteStripeEvents();
    checkout.deleteAll();
  }

  private void deleteStripeEvents() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "do $$ begin if to_regclass('stripe_event') is not null then"
              + " execute 'delete from stripe_event'; end if; end $$");
    }
  }

  // --- stripe_event ---------------------------------------------------------------------------

  long countStripeEvents() throws SQLException {
    requireStripeEvent();
    return longValue("select count(*) from stripe_event");
  }

  /** The row of the event, or null. */
  Map<String, Object> stripeEvent(String id) throws SQLException {
    requireStripeEvent();
    List<Map<String, Object>> found = rows("select * from stripe_event where id = ?", id);
    return found.isEmpty() ? null : found.get(0);
  }

  /** A row left by an earlier delivery whose processing failed: processed_at null. */
  void insertUnprocessed(String id, String type, String account) throws SQLException {
    requireStripeEvent();
    update(
        "insert into stripe_event (id, type, account, received_at, processed_at)"
            + " values (?, ?, ?, now() - interval '1 minute', null)",
        id,
        type,
        account);
  }

  private void requireStripeEvent() throws SQLException {
    assertTrue(
        longValue("select count(*) from pg_class where relname = 'stripe_event'") > 0,
        "table stripe_event does not exist (V6 missing)");
  }

  // --- orders ---------------------------------------------------------------------------------

  Map<String, Object> order(long id) throws SQLException {
    List<Map<String, Object>> found = rows("select * from customer_order where id = ?", id);
    assertTrue(!found.isEmpty(), "no order " + id);
    return found.get(0);
  }

  String status(long orderId) throws SQLException {
    return (String) order(orderId).get("status");
  }

  /** Column of the order that must exist (added by V6). */
  Object column(long orderId, String column) throws SQLException {
    Map<String, Object> order = order(orderId);
    assertTrue(order.containsKey(column), "customer_order has no column " + column);
    return order.get(column);
  }

  List<Map<String, Object>> events(long orderId) throws SQLException {
    return checkout.events(orderId);
  }

  /** Order events to the given status. */
  long eventsTo(long orderId, String to) throws SQLException {
    return events(orderId).stream().filter(e -> to.equals(e.get("to_status"))).count();
  }

  void setTenantStatus(String tenantId, String status) throws SQLException {
    update("update tenant set status = ? where id = ?", status, tenantId);
  }

  // --- JDBC -----------------------------------------------------------------------------------

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
            row.put(meta.getColumnName(i), rows.getObject(i));
          }
          found.add(row);
        }
        return found;
      }
    }
  }

  private long longValue(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query = connection.prepareStatement(sql)) {
      bind(query, parameters);
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next(), "no row for " + sql);
        return rows.getLong(1);
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
