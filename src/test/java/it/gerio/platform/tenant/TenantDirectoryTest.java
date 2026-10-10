package it.gerio.platform.tenant;

import static it.gerio.platform.tenant.TestShops.SHOP_A;
import static it.gerio.platform.tenant.TestShops.SHOP_B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// TenantDirectory called directly, as future callers will (admin area, domain
// verification, the TLS permission endpoint of §11.4). The HTTP filter never
// passes it a host below the base domain, so TenantRequestFilterTest cannot
// prove its own guard.
@QuarkusTest
class TenantDirectoryTest {

  @Inject DataSource dataSource;
  @Inject TenantContext tenantContext;
  @Inject TenantDirectory directory;

  private TestShops shops;

  @BeforeEach
  void shops() throws SQLException {
    shops = new TestShops(dataSource);
    shops.insertTenant(SHOP_A, "fioraia-a", "Fioraia A", TenantStatus.ACTIVE);
    shops.insertTenant(SHOP_B, "fioraia-b", "Fioraia B", TenantStatus.ACTIVE);
    // A row that should never exist: A's free subdomain registered as B's domain.
    shops.insertDomain(SHOP_B, "fioraia-a.localhost", false, true);
    shops.insertDomain(SHOP_B, "fioraia-b.it", true, true);
    tenantContext.set(CurrentTenant.PLATFORM);
  }

  @AfterEach
  void cleanUp() throws SQLException {
    shops.deleteAll();
  }

  @Test
  void findsAVerifiedCustomDomain() {
    assertEquals(SHOP_B, directory.findByHostname("fioraia-b.it").orElseThrow().id());
  }

  @Test
  void neverResolvesAHostBelowTheBaseDomainThroughTenantDomain() {
    assertTrue(directory.findByHostname("fioraia-a.localhost").isEmpty());
  }

  @Test
  void neverResolvesTheBaseDomainItself() {
    assertTrue(directory.findByHostname("localhost").isEmpty());
  }
}
