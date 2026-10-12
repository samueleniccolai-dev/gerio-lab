package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro008Browser.NBSP;
import static it.gerio.platform.acceptance.Giro008Browser.body;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro008CartData.MARCO;
import static it.gerio.platform.acceptance.Giro008CartData.PLATFORM;
import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.EXPIRED;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.expiredSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.paidSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.placeDemoOrder;
import static it.gerio.platform.acceptance.Giro011Webhooks.sendOk;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 011: the outcome page {@code GET /ordine/esito?session_id=...} where Stripe sends the
 * customer back. Only on the shop host and only for a session of that shop; it shows number, status
 * and total, never the customer's name or email (docs/lab/giri/011-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro011OrderOutcomeAcceptanceTest {

  private static final Instant T0 = Giro010CheckoutAcceptanceTest.T0;
  private static final Pattern META_REFRESH =
      Pattern.compile("<meta[^>]*http-equiv=[\"']refresh[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
  // The apostrophe may be typed or escaped by the template engine.
  private static final String APOSTROPHE = "(?:'|’|&#39;|&#x27;|&apos;)";
  private static final Pattern EMAIL_SOON =
      Pattern.compile("Riceverai l" + APOSTROPHE + "email di conferma");
  private static final Pattern EMAIL_PAID =
      Pattern.compile("Riceverai un" + APOSTROPHE + "email di conferma");

  @Inject DataSource dataSource;

  private Giro011Data data;
  private Giro010FakeStripe stripe;
  private final TestClock clock = new TestClock(T0);

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

  private static Response outcome(String host, String query) {
    return given()
        .redirects()
        .follow(false)
        .header("Host", host)
        .when()
        .get("/ordine/esito" + query);
  }

  private static String page(String host, String query) {
    Response response = outcome(host, query);
    assertEquals(200, response.statusCode(), "GET /ordine/esito" + query + " on " + host);
    return body(response);
  }

  private static void assertNoPersonalData(String html, String what) {
    assertFalse(html.contains("Giulia"), what + ": shows the customer's name");
    assertFalse(html.contains(Giro010Checkout.EMAIL), what + ": shows the customer's email");
    assertFalse(html.contains("333 000 1234"), what + ": shows the customer's phone");
  }

  @Test
  void afterThePaymentThePageSaysReceivedWithNumberAndTotal() throws Exception {
    Placed order = placeDemoOrder(data.checkout, stripe);
    sendOk(
        event(
            "evt_giro011_paid_1",
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(order, order.sessionId(), "pi_giro011_demo_1")),
        "paid");

    String html = page(DEMO, "?session_id=" + order.sessionId());
    assertTrue(html.contains("Pagamento ricevuto"), "no \"Pagamento ricevuto\"");
    assertTrue(html.contains("2026-0001"), "no order number");
    assertTrue(html.contains("104,80" + NBSP + "€"), "no total 104,80 €");
    assertTrue(EMAIL_PAID.matcher(html).find(), "no \"Riceverai un'email di conferma\"");
    assertFalse(META_REFRESH.matcher(html).find(), "a paid order must not reload");
    assertNoPersonalData(html, "paid");
  }

  @Test
  void whilePendingThePageReloadsUpToFifteenTimes() throws Exception {
    Placed order = placeDemoOrder(data.checkout, stripe);
    String id = order.sessionId();

    String first = page(DEMO, "?session_id=" + id);
    assertTrue(first.contains("Stiamo confermando il pagamento"), "no waiting message");
    assertRefreshTo(first, id, 1, "first visit");
    assertNoPersonalData(first, "pending");

    assertRefreshTo(page(DEMO, "?session_id=" + id + "&n=3"), id, 4, "n=3");
    assertRefreshTo(page(DEMO, "?session_id=" + id + "&n=14"), id, 15, "n=14");

    for (String n : List.of("15", "abc", "16", "-1", "999999999999")) {
      String html = page(DEMO, "?session_id=" + id + "&n=" + n);
      assertFalse(META_REFRESH.matcher(html).find(), "n=" + n + ": still reloads");
      assertTrue(EMAIL_SOON.matcher(html).find(), "n=" + n + ": no \"Riceverai l'email\"");
    }
  }

  private static void assertRefreshTo(String html, String sessionId, int n, String what) {
    Matcher meta = META_REFRESH.matcher(html);
    assertTrue(meta.find(), what + ": no <meta http-equiv=refresh>");
    String tag = meta.group();
    Pattern content =
        Pattern.compile(
            "content=[\"']2;\\s*url=/ordine/esito\\?session_id="
                + Pattern.quote(sessionId)
                + "&(?:amp;)?n="
                + n
                + "[\"']");
    assertTrue(content.matcher(tag).find(), what + ": meta refresh is " + tag);
  }

  @Test
  void afterTheExpiryThePageSaysNotCompletedWithALinkToTheCart() throws Exception {
    Placed order = placeDemoOrder(data.checkout, stripe);
    sendOk(
        event(
            "evt_giro011_expired_1",
            EXPIRED,
            DEMO_ACCOUNT,
            expiredSession(order, order.sessionId())),
        "expired");

    String html = page(DEMO, "?session_id=" + order.sessionId());
    assertTrue(
        html.contains("Il pagamento non è stato completato"), "no \"pagamento non completato\"");
    assertTrue(
        Pattern.compile("<a[^>]*href=[\"']/carrello[\"'][^>]*>\\s*Torna al carrello")
            .matcher(html)
            .find(),
        "no link \"Torna al carrello\" to /carrello");
    assertFalse(META_REFRESH.matcher(html).find(), "an expired order must not reload");
    assertNoPersonalData(html, "expired");
  }

  @Test
  void aPaymentOnAnOlderSessionShowsTheOutcomeForThatSession() throws Exception {
    Placed first = placeDemoOrder(data.checkout, stripe);
    clock.set(T0.plus(Duration.ofMinutes(32)));
    Placed second =
        Giro011Webhooks.checkout(first.browser(), data.checkout, stripe, Giro005DemoData.DEMO_ID);
    assertEquals(first.orderId(), second.orderId(), "same order on the second attempt");

    sendOk(
        event(
            "evt_giro011_paid_old",
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(first, first.sessionId(), "pi_giro011_old")),
        "paid on the older session");
    String html = page(DEMO, "?session_id=" + first.sessionId());
    assertTrue(
        html.contains("Pagamento ricevuto"), "older paid session: no \"Pagamento ricevuto\"");
  }

  @Test
  void unknownSessionsOtherShopsAndThePlatformGet404() throws Exception {
    Placed order = placeDemoOrder(data.checkout, stripe);
    // Positive control: the session works on its own shop.
    assertEquals(200, outcome(DEMO, "?session_id=" + order.sessionId()).statusCode(), "own shop");

    assertEquals(
        404, outcome(DEMO, "?session_id=cs_test_giro011_inesistente").statusCode(), "unknown id");
    assertEquals(404, outcome(DEMO, "").statusCode(), "no session_id");
    assertEquals(404, outcome(DEMO, "?session_id=").statusCode(), "empty session_id");
    assertEquals(
        404,
        outcome(MARCO, "?session_id=" + order.sessionId()).statusCode(),
        "demo session on Marco's host");
    assertEquals(
        404, outcome(PLATFORM, "?session_id=" + order.sessionId()).statusCode(), "platform domain");
  }
}
