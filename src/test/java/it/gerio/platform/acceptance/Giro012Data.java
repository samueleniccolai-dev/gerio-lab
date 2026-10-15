package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.StartupEvent;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Giro 012 test data: the shops of giro 011 (demo and Marco, both connected, no orders, no Stripe
 * events), plain-JDBC reads and writes of the Stripe columns of {@code tenant}, and a simulated
 * restart.
 *
 * <p>The restart fires the CDI {@link StartupEvent} again, which runs every startup step of the
 * application ({@code @Startup} methods and observers) with the profile's configuration. To prove
 * that the startup steps really ran, the demo's name is changed first and must be back to the
 * seed's "Fioreria Il Glicine" afterwards.
 */
final class Giro012Data {

  static final String DEMO_NAME = "Fioreria Il Glicine";

  private final DataSource dataSource;
  final Giro011Data webhooks;

  /** Stripe columns of one shop. */
  record Stripe(String account, boolean chargesEnabled) {}

  Giro012Data(DataSource dataSource) throws IOException, SQLException {
    this.dataSource = dataSource;
    webhooks = new Giro011Data(dataSource);
  }

  Giro010CheckoutData checkout() {
    return webhooks.checkout;
  }

  void deleteAll() throws SQLException {
    webhooks.deleteAll();
  }

  /** Writes stripe_account_id (null allowed) and stripe_charges_enabled of a shop. */
  void connect(String tenantId, String account, boolean chargesEnabled) throws SQLException {
    webhooks.checkout.connect(tenantId, account, chargesEnabled);
  }

  Stripe stripe(String tenantId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "select stripe_account_id, stripe_charges_enabled from tenant where id = ?")) {
      query.setString(1, tenantId);
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next(), "no tenant " + tenantId);
        return new Stripe(rows.getString(1), rows.getBoolean(2));
      }
    }
  }

  long countTenantsWithAccount(String account) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "select count(*) from tenant where stripe_account_id = ?")) {
      query.setString(1, account);
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private String demoName() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement("select display_name from tenant where id = ?")) {
      query.setString(1, DEMO_ID);
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next(), "no demo tenant");
        return rows.getString(1);
      }
    }
  }

  private void renameDemo(String name) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("update tenant set display_name = ? where id = ?")) {
      statement.setString(1, name);
      statement.setString(2, DEMO_ID);
      statement.executeUpdate();
    }
  }

  /**
   * Simulated restart with the profile's configuration: the startup steps run again and must not
   * throw (a failing startup step would stop the real application).
   */
  void restart() throws SQLException {
    renameDemo("Glicine prima del riavvio");
    assertDoesNotThrow(
        () ->
            Arc.container()
                .beanManager()
                .getEvent()
                .select(StartupEvent.class)
                .fire(new StartupEvent()),
        "a startup step failed on restart");
    assertEquals(DEMO_NAME, demoName(), "the demo seed did not run on the simulated restart");
  }
}
