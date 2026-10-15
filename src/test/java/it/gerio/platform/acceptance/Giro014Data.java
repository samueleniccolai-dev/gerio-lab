package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Giro 014 test data (docs/lab/giri/014-piano.md): the data of giro 011, the public token of an
 * order read with plain JDBC, an order of Marco's shop written with plain JDBC, and helpers that
 * read pages as plain text.
 */
final class Giro014Data {

  /** A token of the right shape that no order has. */
  static final String UNKNOWN_TOKEN = "A".repeat(43);

  /** The token of the order of Marco's shop written by {@link #insertMarcoOrder()}. */
  static final String MARCO_TOKEN = "Giro014MarcoToken_abcdefghijklmnopqrstuvwxy";

  private final DataSource dataSource;
  final Giro011Data giro011;

  Giro014Data(DataSource dataSource) throws IOException, SQLException {
    this.dataSource = dataSource;
    giro011 = new Giro011Data(dataSource);
  }

  void deleteAll() throws SQLException {
    giro011.deleteAll();
  }

  String token(long orderId) throws SQLException {
    Object token = giro011.order(orderId).get("public_token");
    assertNotNull(token, "order " + orderId + " has no public_token");
    return (String) token;
  }

  /** A paid pickup order 2026-0001 of Marco's shop (no items), with {@link #MARCO_TOKEN}. */
  void insertMarcoOrder() throws SQLException {
    assertTrue(MARCO_TOKEN.matches("[A-Za-z0-9_-]{43}"), "MARCO_TOKEN has the wrong shape");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "insert into customer_order (tenant_id, number, public_token, status,"
                    + " fulfillment_type, customer_name, customer_email, delivery_date,"
                    + " subtotal_cents, delivery_fee_cents, total_cents, vat_breakdown,"
                    + " terms_version, created_at, updated_at)"
                    + " values (?, '2026-0001', ?, 'PAID', 'PICKUP', 'Paolo Verdi',"
                    + " 'paolo.verdi@example.com', date '2026-10-13', 2500, 0, 2500,"
                    + " '{}'::jsonb, 'giro014', timestamptz '2026-10-12 08:00:00+00',"
                    + " timestamptz '2026-10-12 08:00:00+00')")) {
      insert.setString(1, Giro005DemoData.MARCO_ID);
      insert.setString(2, MARCO_TOKEN);
      insert.executeUpdate();
    }
  }

  // --- pages ----------------------------------------------------------------------------------

  private static final Pattern TAG = Pattern.compile("<[^>]*>");

  /** The page as text: tags become spaces, runs of white space (NBSP excluded) become one. */
  static String text(String html) {
    return TAG.matcher(html).replaceAll(" ").replaceAll("[ \\t\\r\\n]+", " ");
  }

  /** The {@code <main>} element of the page, or the empty string. */
  static String main(String html) {
    Matcher m = Pattern.compile("(?s)<main[^>]*>(.*)</main>").matcher(html);
    return m.find() ? m.group(1) : "";
  }

  static String body(Response response) {
    return Giro008Browser.body(response);
  }
}
