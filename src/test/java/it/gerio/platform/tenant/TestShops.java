package it.gerio.platform.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Test data written with plain JDBC: it bypasses Hibernate and the tenant filter, so a test can
 * prepare two shops at once and then check with its own eyes what reached the database.
 */
public final class TestShops {

  public static final String SHOP_A = "00000000-0000-0000-0000-00000000000a";
  public static final String SHOP_B = "00000000-0000-0000-0000-00000000000b";

  private final DataSource dataSource;

  public TestShops(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void insertTenant(String id, String slug, String displayName, TenantStatus status)
      throws SQLException {
    update(
        "insert into tenant (id, slug, display_name, legal_name, email, status)"
            + " values (?, ?, ?, ?, ?, ?)",
        id,
        slug,
        displayName,
        displayName + " Srl",
        slug + "@example.test",
        status.name());
  }

  public void insertDomain(String tenantId, String hostname, boolean primary, boolean verified)
      throws SQLException {
    update(
        "insert into tenant_domain (tenant_id, hostname, is_primary, verified_at)"
            + " values (?, ?, ?, case when ? then now() end)",
        tenantId,
        hostname,
        primary,
        verified);
  }

  /** Inserts a probe_item row for the given shop and returns its id. */
  public long insertProbe(String tenantId, String name) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "insert into probe_item (tenant_id, name) values (?, ?) returning id")) {
      insert.setString(1, tenantId);
      insert.setString(2, name);
      try (ResultSet rows = insert.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  /** Name and owner of a probe_item row, read without any tenant filter; null if missing. */
  public String probeRow(long id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement("select tenant_id, name from probe_item where id = ?")) {
      query.setLong(1, id);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? rows.getString("tenant_id") + "/" + rows.getString("name") : null;
      }
    }
  }

  public long countAllProbes() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("select count(*) from probe_item")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  public void deleteAll() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("delete from probe_item");
      statement.execute("delete from product_variant");
      statement.execute("delete from product");
      statement.execute("delete from category");
      statement.execute("delete from tenant_domain");
      statement.execute("delete from tenant");
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
}
