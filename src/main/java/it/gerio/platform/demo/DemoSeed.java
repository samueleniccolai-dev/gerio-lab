package it.gerio.platform.demo;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
