package it.gerio.platform.tenant;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.tenant.TestShops.SHOP_A;
import static it.gerio.platform.tenant.TestShops.SHOP_B;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// PROGETTO.md §5.3 end to end: the Host picks the shop (HTTP filter), and a
// naive endpoint that trusts the id in the URL still cannot reach B's rows.
// Every future endpoint on shop data needs a test like this one.
@QuarkusTest
class EndpointIsolationTest {

  @Inject DataSource dataSource;

  private TestShops shops;
  private long a1;
  private long b1;

  @BeforeEach
  void twoShopsWithTheirRows() throws SQLException {
    shops = new TestShops(dataSource);
    shops.insertTenant(SHOP_A, "fioraia-a", "Fioraia A", TenantStatus.ACTIVE);
    shops.insertTenant(SHOP_B, "fioraia-b", "Fioraia B", TenantStatus.ACTIVE);
    a1 = shops.insertProbe(SHOP_A, "a-1");
    b1 = shops.insertProbe(SHOP_B, "b-1");
  }

  @AfterEach
  void cleanUp() throws SQLException {
    shops.deleteAll();
  }

  // Positive controls: without them, a 404 everywhere would also pass the tests below.
  @Test
  void eachShopReadsAndRenamesItsOwnRow() throws SQLException {
    as("fioraia-a.localhost").get("/probe-items/" + a1).then().statusCode(200).body(equalTo("a-1"));
    as("fioraia-b.localhost").get("/probe-items/" + b1).then().statusCode(200).body(equalTo("b-1"));

    as("fioraia-a.localhost").body("a-nuovo").put("/probe-items/" + a1).then().statusCode(200);
    assertEquals(SHOP_A + "/a-nuovo", shops.probeRow(a1));
  }

  @Test
  void aCannotReadBEvenWithTheIdOfBInTheUrl() {
    as("fioraia-a.localhost").get("/probe-items/" + b1).then().statusCode(404);
  }

  @Test
  void aCannotChangeBEvenWithTheIdOfBInTheUrl() throws SQLException {
    as("fioraia-a.localhost").body("rubato").put("/probe-items/" + b1).then().statusCode(404);

    assertEquals(SHOP_B + "/b-1", shops.probeRow(b1));
  }

  // The platform domain is not a way around the filter either.
  @Test
  void thePlatformDomainSeesNoShopRow() {
    as("localhost").get("/probe-items/" + a1).then().statusCode(404);
  }

  private static RequestSpecification as(String host) {
    return given().redirects().follow(false).header("Host", host).contentType("text/plain").when();
  }
}
