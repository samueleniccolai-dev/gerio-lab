package it.gerio.platform.demo;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Loads the invented demo shop ({@code demo/demo-shop.sql}) at startup when {@code app.demo.seed}
 * is true (dev and prod; off elsewhere). Runs after Flyway, which migrates when the datasource is
 * first used. The file is idempotent, so a restart changes nothing.
 */
@ApplicationScoped
public class DemoSeed {

  static final String RESOURCE = "demo/demo-shop.sql";
  private static final Logger LOG = Logger.getLogger(DemoSeed.class);

  @Inject DataSource dataSource;

  @ConfigProperty(name = "app.demo.seed")
  boolean enabled;

  @ConfigProperty(name = "app.demo.stripe-account-id")
  Optional<String> stripeAccountId;

  @Startup
  void seed() {
    if (!enabled) {
      return;
    }
    String sql = read();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException("Demo seed failed", e);
    }
    LOG.info("Demo shop seeded");
    connectStripeAccount();
  }

  /** Separate statement: a unique conflict keeps the previous value and does not stop startup. */
  private void connectStripeAccount() {
    String account = stripeAccountId.map(String::trim).filter(a -> !a.isEmpty()).orElse(null);
    if (account == null) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update tenant set stripe_account_id = ?, stripe_charges_enabled = true"
                    + " where id = '00000000-0000-0000-0000-0000000de000'")) {
      statement.setString(1, account);
      statement.executeUpdate();
    } catch (SQLException e) {
      LOG.errorf("Stripe account %s not applied to the demo shop: %s", account, e.getSQLState());
    }
  }

  private static String read() {
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is not on the classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
