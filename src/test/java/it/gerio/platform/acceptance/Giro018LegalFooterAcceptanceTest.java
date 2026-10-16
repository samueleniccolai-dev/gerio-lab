package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.paidSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.placeDemoOrder;
import static it.gerio.platform.acceptance.Giro011Webhooks.sendOk;
import static it.gerio.platform.acceptance.Giro018Legal.assertDemoFooter;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 018 (goal T8): every page of the storefront has the footer with the seller's data and the
 * five legal links, and no page sets a cookie other than the cart and the CSRF one
 * (docs/lab/giri/018-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro018LegalFooterAcceptanceTest {

  /** The GET pages of the storefront that need no cart and no order. */
  private static final List<String> PAGES =
      List.of(
          "/",
          "/catalogo",
          "/catalogo/bouquet",
          "/prodotti/bouquet-di-stagione",
          "/carrello",
          "/informazioni-venditore",
          "/privacy",
          "/cookie",
          "/accessibilita");

  /** The only cookies the storefront may set (SPEC §12: technical cookies only). */
  private static final Set<String> ALLOWED_COOKIES =
      Set.of(Giro008Browser.CART_COOKIE, Giro008Browser.CSRF);

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

  @Test
  void everyPageOfTheStorefrontHasTheFooterWithTheSellerAndTheFiveLinks() {
    for (String path : PAGES) {
      assertDemoFooter(Giro018Legal.page(DEMO, path), "GET " + path);
    }
  }

  @Test
  void theCheckoutWithAFullCartHasTheFooter() {
    Giro008Browser browser = new Giro008Browser(DEMO);
    Giro010Checkout.fillDemoCart(browser, data.giro011.checkout);
    assertDemoFooter(browser.page("/checkout", 200), "GET /checkout with a full cart");
  }

  @Test
  void theOutcomePageAndTheOrderStatusPageHaveTheFooter() throws Exception {
    Placed order = placeDemoOrder(data.giro011.checkout, stripe);
    sendOk(
        event(
            "evt_giro018_paid_1",
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(order, order.sessionId(), "pi_giro018_demo_1")),
        "paid");

    String outcome = Giro018Legal.page(DEMO, "/ordine/esito?session_id=" + order.sessionId());
    assertDemoFooter(outcome, "outcome page");
    String status = Giro018Legal.page(DEMO, "/ordine/" + data.token(order.orderId()));
    assertDemoFooter(status, "order status page");
  }

  @Test
  void theStorefrontSetsOnlyTheCartAndCsrfCookiesAndTheCookiePageNamesThem() {
    Giro008Browser browser = new Giro008Browser(DEMO);
    Set<String> seen = new TreeSet<>();
    for (String path : PAGES) {
      seen.addAll(names(browser.get(path), "GET " + path));
    }
    Giro010Checkout.fillDemoCart(browser, data.giro011.checkout);
    seen.addAll(browser.cookies.keySet());
    seen.addAll(names(browser.get("/checkout"), "GET /checkout with a full cart"));
    for (String path : List.of("/", "/carrello", "/cookie")) {
      seen.addAll(names(browser.get(path), "GET " + path + " with a full cart"));
    }

    assertTrue(
        ALLOWED_COOKIES.containsAll(seen),
        "the storefront sets cookies other than cart and " + Giro008Browser.CSRF + ": " + seen);
    assertTrue(seen.contains(Giro008Browser.CART_COOKIE), "no cart cookie after adding: " + seen);

    String cookiePage = Giro018Legal.text(Giro018Legal.page(DEMO, "/cookie"));
    for (String name : seen) {
      assertTrue(cookiePage.contains(name), "the cookie page does not name «" + name + "»");
    }
  }

  /** The names of the cookies set by the response, checked to be technical only. */
  private static Set<String> names(Response response, String where) {
    assertEquals(200, response.statusCode(), where);
    Set<String> found = new TreeSet<>();
    for (String header : response.getHeaders().getValues("Set-Cookie")) {
      int eq = header.indexOf('=');
      String name = eq > 0 ? header.substring(0, eq).trim() : header.trim();
      assertTrue(
          ALLOWED_COOKIES.contains(name), where + ": sets a non-technical cookie «" + header + "»");
      found.add(name);
    }
    return found;
  }
}
