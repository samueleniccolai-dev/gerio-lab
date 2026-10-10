package it.gerio.platform.storefront;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.tenant.TestShops.SHOP_A;
import static it.gerio.platform.tenant.TestShops.SHOP_B;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import it.gerio.platform.tenant.TenantStatus;
import it.gerio.platform.tenant.TestShops;
import jakarta.inject.Inject;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// The R0 probe page: each host shows its own shop, never another one.
@QuarkusTest
class StorefrontPageTest {

  private static final String XSS = "00000000-0000-0000-0000-0000000000cc";

  @Inject DataSource dataSource;

  private TestShops shops;

  @BeforeEach
  void shops() throws SQLException {
    shops = new TestShops(dataSource);
    shops.insertTenant(SHOP_A, "fioraia-a", "Fioraia Anna", TenantStatus.ACTIVE);
    shops.insertTenant(SHOP_B, "fioraia-b", "Fiori di Bruno", TenantStatus.ACTIVE);
    shops.insertDomain(SHOP_B, "fioridibruno.it", true, true);
    shops.insertTenant(XSS, "fioraia-x", "Fiori <script>alert(1)</script>", TenantStatus.ACTIVE);
  }

  @AfterEach
  void cleanUp() throws SQLException {
    shops.deleteAll();
  }

  @Test
  void theHostOfAShowsAAndNeverB() {
    home("fioraia-a.localhost")
        .statusCode(200)
        .body(containsString("<h1>Fioraia Anna</h1>"))
        .body(not(containsString("Bruno")));
  }

  @Test
  void theCustomDomainOfBShowsBAndNeverA() {
    home("fioridibruno.it")
        .statusCode(200)
        .body(containsString("<h1>Fiori di Bruno</h1>"))
        .body(not(containsString("Anna")));
  }

  @Test
  void thePlatformDomainShowsTheLandingAndNoShop() {
    home("localhost")
        .statusCode(200)
        .body(containsString("Negozi locali online"))
        .body(not(containsString("Anna")))
        .body(not(containsString("Bruno")));
  }

  // A shop owner types markup in the shop name: Qute must escape it.
  @Test
  void theShopNameIsEscaped() {
    home("fioraia-x.localhost")
        .statusCode(200)
        .body(containsString("Fiori &lt;script&gt;alert(1)&lt;/script&gt;"))
        .body(not(containsString("<script>")));
  }

  private static ValidatableResponse home(String host) {
    return given().redirects().follow(false).header("Host", host).when().get("/").then();
  }
}
