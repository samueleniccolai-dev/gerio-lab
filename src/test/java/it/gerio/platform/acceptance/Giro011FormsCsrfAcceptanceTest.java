package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro008Browser.body;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 011, critique point 1: letting the JSON webhook through the CSRF filter must not open the
 * shop's forms to requests of other media types. A POST without the CSRF token, as JSON or as
 * text/plain (a "simple" cross-site request) or with no Content-Type at all, changes neither the
 * cart nor the orders. Guards: they may already pass; they must stay green after the change of the
 * CSRF configuration.
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro011FormsCsrfAcceptanceTest {

  @Inject DataSource dataSource;

  private Giro011Data data;
  private Giro010FakeStripe stripe;
  private final TestClock clock = new TestClock(Giro010CheckoutAcceptanceTest.T0);

  @BeforeEach
  void setUp() throws Exception {
    QuarkusMock.installMockForType(clock, Clock.class);
    stripe = Giro010FakeStripe.get();
    stripe.reset();
    data = new Giro011Data(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  private static Response raw(Giro008Browser browser, String path, String type, String content) {
    return given()
        .redirects()
        .follow(false)
        .header("Host", DEMO)
        .cookies(browser.cookies)
        .contentType(type)
        .body(content)
        .when()
        .post(path);
  }

  /** POST with no Content-Type and no body, as {@code fetch(url, {method: "POST"})} sends it. */
  private static Response noType(Giro008Browser browser, String path) {
    return given()
        .redirects()
        .follow(false)
        .header("Host", DEMO)
        .cookies(browser.cookies)
        .when()
        .post(path);
  }

  private static void assertRejected(Response response, String what) {
    int status = response.statusCode();
    assertTrue(
        status >= 400 && status < 500,
        what + ": expected a 4xx, got " + status + "; body: " + body(response));
  }

  @Test
  void cartFormsRefuseJsonAndPlainTextWithoutToken() {
    Giro008Browser browser = new Giro008Browser(DEMO);
    Giro010Checkout.fillDemoCart(browser, data.checkout);
    String before = browser.summary();
    long orchid = data.checkout.cart.orchid;

    assertRejected(
        raw(
            browser,
            "/carrello/aggiungi",
            "application/json",
            "{\"variantId\":\"" + orchid + "\",\"quantity\":\"5\"}"),
        "JSON on /carrello/aggiungi");
    assertRejected(
        raw(browser, "/carrello/aggiungi", "text/plain", "variantId=" + orchid + "&quantity=5"),
        "text/plain on /carrello/aggiungi");
    long item = Giro008Browser.itemId(browser.cartPage(), "orchidea-bianca");
    assertRejected(
        raw(browser, "/carrello/righe/" + item + "/rimuovi", "text/plain", ""),
        "text/plain on /carrello/righe/{id}/rimuovi");
    assertRejected(
        noType(browser, "/carrello/righe/" + item + "/rimuovi"),
        "no Content-Type on /carrello/righe/{id}/rimuovi");
    assertRejected(
        raw(browser, "/carrello/righe/" + item, "application/json", "{\"quantity\":\"9\"}"),
        "JSON on /carrello/righe/{id}");

    assertEquals(before, browser.summary(), "the cart changed");
  }

  @Test
  void checkoutRefusesJsonAndPlainTextWithoutToken() throws SQLException {
    Giro008Browser browser = new Giro008Browser(DEMO);
    Giro010Checkout.fillDemoCart(browser, data.checkout);
    browser.page("/checkout", 200);

    assertRejected(
        raw(
            browser,
            "/checkout",
            "application/json",
            "{\"customerName\":\"Giulia Bianchi\",\"customerEmail\":\"giulia.bianchi@example.com\","
                + "\"pickupDate\":\"2026-10-13\"}"),
        "JSON on /checkout");
    assertRejected(
        raw(
            browser,
            "/checkout",
            "text/plain",
            "customerName=Giulia+Bianchi&customerEmail=giulia.bianchi%40example.com"
                + "&pickupDate=2026-10-13"),
        "text/plain on /checkout");
    assertRejected(noType(browser, "/checkout"), "no Content-Type on /checkout");

    assertEquals(0, data.checkout.countOrders(), "orders created");
    assertEquals(0, stripe.allCalls().size(), "Stripe was called");
  }
}
