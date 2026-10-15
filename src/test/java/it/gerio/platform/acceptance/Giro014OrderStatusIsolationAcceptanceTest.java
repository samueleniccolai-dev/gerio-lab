package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro008CartData.MARCO;
import static it.gerio.platform.acceptance.Giro008CartData.PLATFORM;
import static it.gerio.platform.acceptance.Giro011Webhooks.placeDemoOrder;
import static it.gerio.platform.acceptance.Giro014Data.MARCO_TOKEN;
import static it.gerio.platform.acceptance.Giro014Data.UNKNOWN_TOKEN;
import static it.gerio.platform.acceptance.Giro014Data.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import it.gerio.platform.acceptance.Giro011Webhooks.Placed;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 014: the status page {@code GET /ordine/{token}} answers 404 with the same «not found» page
 * for an unknown or malformed token, for a token of another shop (both ways) and on the platform
 * domain (docs/lab/giri/014-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro014OrderStatusIsolationAcceptanceTest {

  @Inject DataSource dataSource;

  private Giro014Data data;
  private Giro010FakeStripe stripe;
  private final TestClock clock = new TestClock(Giro010CheckoutAcceptanceTest.T0);

  @BeforeEach
  void setUp() throws Exception {
    QuarkusMock.installMockForType(clock, Clock.class);
    stripe = Giro010FakeStripe.get();
    stripe.reset();
    data = new Giro014Data(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  private static Response get(String host, String token) {
    return given().redirects().follow(false).header("Host", host).when().get("/ordine/" + token);
  }

  /** 404 with the «Pagina non trovata» page; returns the body. */
  private static String notFound(String host, String token, String what) {
    Response response = get(host, token);
    String body = body(response);
    assertEquals(404, response.statusCode(), what + ": status; body: " + body);
    assertTrue(body.contains("Pagina non trovata"), what + ": not the «not found» page: " + body);
    return body;
  }

  @Test
  void aTokenOfOneShopIsNotFoundOnTheOtherShopNorOnThePlatform() throws Exception {
    Placed order = placeDemoOrder(data.giro011.checkout, stripe);
    String demoToken = data.token(order.orderId());
    data.insertMarcoOrder();

    // Positive controls: each token works on its own shop.
    Response own = get(DEMO, demoToken);
    assertEquals(200, own.statusCode(), "demo token on the demo host; " + body(own));
    Response marcoOwn = get(MARCO, MARCO_TOKEN);
    assertEquals(200, marcoOwn.statusCode(), "Marco's token on Marco's host; " + body(marcoOwn));
    assertTrue(body(marcoOwn).contains("Fiori di Marco"), "Marco's page without Marco's name");
    assertFalse(body(marcoOwn).contains("Paolo"), "Marco's page shows the customer's name");

    String unknown = notFound(DEMO, UNKNOWN_TOKEN, "unknown token");
    assertEquals(unknown, notFound(MARCO, demoToken, "demo token on Marco's host"), "same page");
    assertEquals(unknown, notFound(DEMO, MARCO_TOKEN, "Marco's token on the demo host"), "same");
    assertEquals(unknown, notFound(PLATFORM, demoToken, "demo token on the platform"), "same");
    assertFalse(unknown.contains("Fioreria Il Glicine"), "the 404 names the shop");
  }

  @Test
  void malformedTokensGetTheSameNotFoundAsAnUnknownOne() throws Exception {
    String unknown = notFound(DEMO, UNKNOWN_TOKEN, "unknown token");
    for (String token :
        List.of(
            "abc", "A".repeat(44), "A".repeat(42), "A".repeat(42) + ".", "A".repeat(42) + "~")) {
      assertEquals(
          unknown, notFound(DEMO, token, "malformed token " + token), "different 404: " + token);
    }
  }
}
