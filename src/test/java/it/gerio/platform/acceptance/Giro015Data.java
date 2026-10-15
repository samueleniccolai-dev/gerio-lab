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
 * Giro 015 test data (docs/lab/giri/015-piano.md): the data of giro 014, the legal data of Marco's
 * shop (invented), and plain-JDBC reads of {@code outbox_message}. The outbox is emptied (when it
 * exists) before orders and shops. A missing V7 table makes the test fail with an assertion.
 */
final class Giro015Data {

  /** Invented legal data of Marco's shop: none of it may appear in a mail of the demo shop. */
  static final String MARCO_VAT = "IT09876543210";

  static final String MARCO_ADDRESS = "Via del Fosso Rosso 99";

  private final DataSource dataSource;
  final Giro014Data giro014;

  Giro015Data(DataSource dataSource) throws IOException, SQLException {
    this.dataSource = dataSource;
    deleteOutbox();
    giro014 = new Giro014Data(dataSource);
    update(
        "update tenant set vat_number = ?, address_line = ?, postal_code = '50100',"
            + " city = 'Firenze', province = 'FI', phone = '+39 055 000000' where id = ?",
        MARCO_VAT,
        MARCO_ADDRESS,
        Giro005DemoData.MARCO_ID);
  }

  void deleteAll() throws SQLException {
    deleteOutbox();
    giro014.deleteAll();
  }

  private void deleteOutbox() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "do $$ begin if to_regclass('outbox_message') is not null then"
              + " execute 'delete from outbox_message'; end if; end $$");
    }
  }

  // --- outbox_message -------------------------------------------------------------------------

  /** All rows, oldest first, with {@code kind} and {@code order_id} read from the payload. */
  List<Map<String, Object>> outbox() throws SQLException {
    requireOutbox();
    return rows(
        "select *, payload->>'kind' as kind, payload->>'orderId' as order_id,"
            + " payload::text as payload_text from outbox_message order by id");
  }

  /** The rows of one order. */
  List<Map<String, Object>> outbox(long orderId) throws SQLException {
    return outbox().stream()
        .filter(r -> String.valueOf(orderId).equals(r.get("order_id")))
        .toList();
  }

  /** The only row of the order. */
  Map<String, Object> onlyRow(long orderId) throws SQLException {
    List<Map<String, Object>> found = outbox(orderId);
    assertTrue(found.size() == 1, "expected 1 outbox row for order " + orderId + ": " + found);
    return found.get(0);
  }

  private void requireOutbox() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("select to_regclass('outbox_message') is not null")) {
      rows.next();
      assertTrue(rows.getBoolean(1), "table outbox_message does not exist (V7 missing)");
    }
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
