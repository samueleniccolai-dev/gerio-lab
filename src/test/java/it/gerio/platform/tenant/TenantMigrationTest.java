package it.gerio.platform.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;

// Checks the V1 migration directly with plain JDBC: no Tenant entity exists yet
// (it arrives with the resolver in a later step), only the schema.
//
// Named *Test, not *IT: Quarkus runs @QuarkusTest classes under Surefire in the
// "test" phase, with Dev Services already up. Failsafe/*IT is for testing a packaged
// (e.g. native) artifact with @QuarkusIntegrationTest, which R0 doesn't need yet.
@QuarkusTest
class TenantMigrationTest {

  // Postgres SQLSTATE codes. Each failure test checks the code *and* the constraint
  // name, so it can only pass for the exact rule it names -- not for a typo, or a
  // future NOT NULL column that makes the insert fail for a different reason.
  private static final String UNIQUE_VIOLATION = "23505";
  private static final String FOREIGN_KEY_VIOLATION = "23503";
  private static final String CHECK_VIOLATION = "23514";

  @Inject DataSource dataSource;

  private Connection connection;

  @BeforeEach
  void openConnection() throws SQLException {
    connection = dataSource.getConnection();
  }

  @AfterEach
  void cleanUpAndClose() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("delete from tenant_domain");
      statement.execute("delete from tenant");
    } finally {
      connection.close();
    }
  }

  @Test
  void insertsATenantWithItsDefaults() throws SQLException {
    insertTenant("tenant-a", "fioraia-a");

    try (PreparedStatement query =
        connection.prepareStatement(
            "select status, stripe_charges_enabled, timezone, created_at, updated_at, version"
                + " from tenant where id = ?")) {
      query.setString(1, "tenant-a");
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next());
        // A brand-new shop must not be public before the admin activates it.
        assertEquals("DRAFT", rows.getString("status"));
        // A brand-new shop must never be able to charge before Stripe is connected.
        assertFalse(rows.getBoolean("stripe_charges_enabled"));
        assertEquals("Europe/Rome", rows.getString("timezone"));
        assertAuditDefaults(rows);
      }
    }
  }

  @Test
  void createsATenantDomain() throws SQLException {
    insertTenant("tenant-a", "fioraia-a");
    insertDomain("tenant-a", "fioraia-a.localhost", true);

    try (PreparedStatement query =
        connection.prepareStatement(
            "select hostname, is_primary, created_at, updated_at, version"
                + " from tenant_domain where tenant_id = ?")) {
      query.setString(1, "tenant-a");
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next());
        assertEquals("fioraia-a.localhost", rows.getString("hostname"));
        assertTrue(rows.getBoolean("is_primary"));
        assertAuditDefaults(rows);
      }
    }
  }

  @Test
  void hostnameIsUniqueAcrossTenants() throws SQLException {
    insertTenant("tenant-a", "fioraia-a");
    insertTenant("tenant-b", "fioraia-b");
    insertDomain("tenant-a", "stessa.localhost", false);

    assertViolation(
        UNIQUE_VIOLATION,
        "tenant_domain_hostname_key",
        () -> insertDomain("tenant-b", "stessa.localhost", false));
  }

  @Test
  void slugIsUniqueAcrossTenants() throws SQLException {
    insertTenant("tenant-a", "fioraia-rossi");

    assertViolation(
        UNIQUE_VIOLATION, "tenant_slug_key", () -> insertTenant("tenant-b", "fioraia-rossi"));
  }

  @Test
  void stripeAccountIdIsUniqueButAllowsSeveralUnconnectedTenants() throws SQLException {
    // Two shops that haven't connected Stripe yet: both stripe_account_id are
    // NULL, and NULL never equals NULL for a unique constraint -- this must work.
    insertTenant("tenant-a", "fioraia-a");
    insertTenant("tenant-b", "fioraia-b");
    setStripeAccount("tenant-a", "acct_shared");

    // §8: the webhook looks up the tenant by Stripe account id, so two shops
    // must never share one -- Stripe would deliver one shop's events to both.
    assertViolation(
        UNIQUE_VIOLATION,
        "tenant_stripe_account_id_key",
        () -> setStripeAccount("tenant-b", "acct_shared"));
  }

  @Test
  void tenantRejectsUnknownStatus() {
    assertViolation(
        CHECK_VIOLATION,
        "tenant_status_known",
        () -> {
          try (PreparedStatement insert =
              connection.prepareStatement(
                  "insert into tenant (id, slug, display_name, legal_name, email, status) "
                      + "values ('tenant-a', 'fioraia-a', 'Fioraia A', 'Fioraia A Srl',"
                      + " 'a@example.test', 'DELETED')")) {
            insert.executeUpdate();
          }
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"__platform__", ""})
  void tenantRejectsReservedOrEmptyId(String id) {
    assertViolation(CHECK_VIOLATION, "tenant_id_not_reserved", () -> insertTenant(id, "fioraia-a"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Fioraia-Rossi", "fioraia_rossi", "-fioraia", "fioraia-", "fioraia.it"})
  void tenantRejectsASlugThatIsNotADnsLabel(String slug) {
    assertViolation(CHECK_VIOLATION, "tenant_slug_dns_label", () -> insertTenant("tenant-a", slug));
  }

  // V2: subdomains the platform keeps for itself.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "www",
        "admin",
        "api",
        "app",
        "internal",
        "mail",
        "static",
        "status",
        "img",
        "staging",
        "dev",
        "test",
        "docs",
        "help",
        "blog",
        "cdn",
        "assets",
        "mta-sts"
      })
  void tenantRejectsAReservedSlug(String slug) {
    assertViolation(
        CHECK_VIOLATION, "tenant_slug_not_reserved", () -> insertTenant("tenant-a", slug));
  }

  // V2: "--" in positions 3-4 is the encoded (punycode) form, shown as Unicode.
  @ParameterizedTest
  @ValueSource(strings = {"xn--fiorai-9ua", "ab--fiori"})
  void tenantRejectsAnEncodedSlug(String slug) {
    assertViolation(
        CHECK_VIOLATION, "tenant_slug_not_encoded", () -> insertTenant("tenant-a", slug));
  }

  // Only the exact names, and "--" only in positions 3-4.
  @ParameterizedTest
  @ValueSource(strings = {"admin-fiori", "wwwfiori", "fiori-app", "fiori--rossi"})
  void tenantAcceptsASlugThatOnlyContainsAReservedName(String slug) throws SQLException {
    insertTenant("tenant-a", slug);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Fioraia-Rossi.it", // uppercase
        "www.fioraia-rossi.it", // the filter strips "www.", so this row is unreachable
        "fioraia-rossi.it:8080", // port
        "fioraia-rossi.it.", // trailing dot
        "fioraia-rossi.it/shop", // path
        "localhost", // no dot: never a real shop domain
        "192.168.1.10" // IP address: never a shop, never an on-demand certificate
      })
  void tenantDomainRejectsAHostnameNotInNormalizedForm(String hostname) throws SQLException {
    insertTenant("tenant-a", "fioraia-a");

    assertViolation(
        CHECK_VIOLATION,
        "tenant_domain_hostname_normalized",
        () -> insertDomain("tenant-a", hostname, false));
  }

  @Test
  void tenantDomainAllowsOnlyOnePrimaryPerTenant() throws SQLException {
    insertTenant("tenant-a", "fioraia-a");
    insertDomain("tenant-a", "uno.localhost", true);

    assertViolation(
        UNIQUE_VIOLATION,
        "tenant_domain_one_primary_idx",
        () -> insertDomain("tenant-a", "due.localhost", true));
  }

  @Test
  void tenantDomainRejectsUnknownTenant() {
    assertViolation(
        FOREIGN_KEY_VIOLATION,
        "tenant_domain_tenant_id_fkey",
        () -> insertDomain("nonexistent", "boh.localhost", false));
  }

  @Test
  void cannotDeleteATenantThatStillHasADomain() throws SQLException {
    insertTenant("tenant-a", "fioraia-a");
    insertDomain("tenant-a", "fioraia-a.localhost", false);

    assertViolation(
        FOREIGN_KEY_VIOLATION,
        "tenant_domain_tenant_id_fkey",
        () -> {
          try (PreparedStatement delete =
              connection.prepareStatement("delete from tenant where id = ?")) {
            delete.setString(1, "tenant-a");
            delete.executeUpdate();
          }
        });
  }

  private void insertTenant(String id, String slug) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "insert into tenant (id, slug, display_name, legal_name, email) "
                + "values (?, ?, 'Fioraia di prova', 'Fioraia di prova Srl', ?)")) {
      insert.setString(1, id);
      insert.setString(2, slug);
      insert.setString(3, id + "@example.test");
      insert.executeUpdate();
    }
  }

  private void insertDomain(String tenantId, String hostname, boolean primary) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "insert into tenant_domain (tenant_id, hostname, is_primary) values (?, ?, ?)")) {
      insert.setString(1, tenantId);
      insert.setString(2, hostname);
      insert.setBoolean(3, primary);
      insert.executeUpdate();
    }
  }

  private void setStripeAccount(String tenantId, String accountId) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement("update tenant set stripe_account_id = ? where id = ?")) {
      update.setString(1, accountId);
      update.setString(2, tenantId);
      update.executeUpdate();
    }
  }

  // ADR-0003: every mutable table has the AuditedEntity columns. The defaults
  // only matter for hand-written SQL; the application sets them from the Clock.
  private static void assertAuditDefaults(ResultSet row) throws SQLException {
    assertNotNull(row.getTimestamp("created_at"));
    assertNotNull(row.getTimestamp("updated_at"));
    assertEquals(0L, row.getLong("version"));
  }

  private static void assertViolation(
      String expectedState, String expectedConstraint, Executable executable) {
    SQLException exception = assertThrows(SQLException.class, executable);
    assertEquals(expectedState, exception.getSQLState());
    PSQLException postgres = assertInstanceOf(PSQLException.class, exception);
    assertEquals(expectedConstraint, postgres.getServerErrorMessage().getConstraint());
  }
}
