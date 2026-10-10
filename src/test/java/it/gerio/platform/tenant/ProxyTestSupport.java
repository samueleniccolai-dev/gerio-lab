package it.gerio.platform.tenant;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.tenant.TestShops.SHOP_A;
import static it.gerio.platform.tenant.TestShops.SHOP_B;

import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** Shared data for {@link ProxyHeadersTest} and {@link TrustedProxyTest}. */
abstract class ProxyTestSupport {

  static final String PATH = "/test/tenant/x";

  @Inject DataSource dataSource;

  private TestShops shops;

  @BeforeEach
  void shops() throws SQLException {
    shops = new TestShops(dataSource);
    shops.insertTenant(SHOP_A, "fioraia-a", "Fioraia A", TenantStatus.ACTIVE);
    shops.insertTenant(SHOP_B, "fioraia-b", "Fioraia B", TenantStatus.ACTIVE);
    shops.insertDomain(SHOP_B, "fioraia-b.it", true, true);
    shops.insertDomain(SHOP_B, "fiori-b.it", false, true);
  }

  @AfterEach
  void cleanUp() throws SQLException {
    shops.deleteAll();
  }

  static RequestSpecification request(String host) {
    return given().redirects().follow(false).header("Host", host).when();
  }
}
