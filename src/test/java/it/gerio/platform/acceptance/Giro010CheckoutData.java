package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro005DemoData.MARCO_ID;
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
 * Giro 010 test data (docs/lab/giri/010-piano.md): the demo seed plus Marco's shop, both with a
 * Stripe connected account and charges enabled; a cleanup that empties the order tables (if they
 * exist) before carts and shops; plain-JDBC reads of what reached the database.
 */
final class Giro010CheckoutData {

  static final String DEMO_ACCOUNT = "acct_1GlicineDemoTest";
  static final String MARCO_ACCOUNT = "acct_1MarcoDemoTest";

  private final DataSource dataSource;
  final Giro008CartData cart;
  final long rose12;

  Giro010CheckoutData(DataSource dataSource) throws IOException, SQLException {
    this.dataSource = dataSource;
    deleteOrders(dataSource);
    cart = new Giro008CartData(dataSource);
    rose12 =
        longValue(
            "select v.id from product_variant v join product p on p.id = v.product_id"
                + " where v.tenant_id = ? and p.slug = 'rose-rosse' and v.name = '12 rose'",
            DEMO_ID);
    connect(DEMO_ID, DEMO_ACCOUNT, true);
    connect(MARCO_ID, MARCO_ACCOUNT, true);
  }

  void deleteAll() throws SQLException {
    deleteOrders(dataSource);
    cart.deleteAll();
  }

  /** Empties order_event, order_item, customer_order and tenant_counter when they exist. */
  static void deleteOrders(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "do $$ declare t text; begin"
              + " foreach t in array array['order_event', 'order_item', 'customer_order',"
              + " 'tenant_counter'] loop"
              + " if to_regclass(t) is not null then execute 'delete from ' || t; end if;"
              + " end loop; end $$");
    }
  }

  void connect(String tenantId, String account, boolean chargesEnabled) throws SQLException {
    update(
        "update tenant set stripe_account_id = ?, stripe_charges_enabled = ? where id = ?",
        account,
        chargesEnabled,
        tenantId);
  }

  void setPrice(long variantId, long cents) throws SQLException {
    update("update product_variant set price_cents = ? where id = ?", cents, variantId);
  }

  /** Rows of customer_order of a shop, oldest first; empty if the table does not exist yet. */
  List<Map<String, Object>> orders(String tenantId) throws SQLException {
    if (!exists("customer_order")) {
      return List.of();
    }
    return rows("select * from customer_order where tenant_id = ? order by id", tenantId);
  }

  long countOrders() throws SQLException {
    if (!exists("customer_order")) {
      return 0;
    }
    return longValue("select count(*) from customer_order");
  }

  List<Map<String, Object>> items(long orderId) throws SQLException {
    return rows("select * from order_item where order_id = ? order by id", orderId);
  }

  List<Map<String, Object>> events(long orderId) throws SQLException {
    return rows("select * from order_event where order_id = ? order by id", orderId);
  }

  private boolean exists(String table) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement("select to_regclass(?) is not null")) {
      query.setString(1, table);
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getBoolean(1);
      }
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
