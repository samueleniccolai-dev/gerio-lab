package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Giro 005 test data: runs the production demo seed from the classpath (as the app does in dev and
 * prod) and adds a second shop, "Fiori di Marco", with a product that reuses the demo slug.
 */
final class Giro005DemoData {

  static final String SEED = "demo/demo-shop.sql";
  static final String DEMO_ID = "00000000-0000-0000-0000-0000000de000";
  static final String MARCO_ID = "00000000-0000-0000-0000-0000000de001";

  private final DataSource dataSource;

  Giro005DemoData(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** Runs the seed file once; fails with an assertion if the file is not on the classpath. */
  void runSeed() throws IOException, SQLException {
    String sql;
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(SEED)) {
      assertNotNull(in, "the demo seed " + SEED + " is not on the classpath");
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  long count(String table, String tenantId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement("select count(*) from " + table + " where tenant_id = ?")) {
      query.setString(1, tenantId);
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  /**
   * Second shop, inserted by hand: category "fiori", product "bouquet-di-stagione", one variant.
   */
  void insertMarco() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      update(
          connection,
          "insert into tenant (id, slug, display_name, legal_name, email, status)"
              + " values (?, 'fiori-di-marco', 'Fiori di Marco', 'Fiori di Marco Srl',"
              + " 'marco@example.test', 'ACTIVE')",
          MARCO_ID);
      long category =
          insertReturningId(
              connection,
              "insert into category (tenant_id, name, slug, position, visible)"
                  + " values (?, 'Fiori', 'fiori', 1, true) returning id",
              MARCO_ID);
      long product =
          insertReturningId(
              connection,
              "insert into product (tenant_id, category_id, name, slug, description, vat_rate_bp,"
                  + " lead_time_days, active, position)"
                  + " values (?, ?, 'Bouquet di stagione', 'bouquet-di-stagione',"
                  + " 'Bouquet di Marco.', 1000, 0, true, 1) returning id",
              MARCO_ID,
              category);
      update(
          connection,
          "insert into product_variant (tenant_id, product_id, name, price_cents, active, position)"
              + " values (?, ?, 'Unico', 3000, true, 1)",
          MARCO_ID,
          product);
    }
  }

  private static long insertReturningId(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private static void update(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
