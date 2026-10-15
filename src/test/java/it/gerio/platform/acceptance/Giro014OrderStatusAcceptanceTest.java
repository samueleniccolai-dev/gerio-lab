package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro008Browser.NBSP;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.EXPIRED;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.expiredSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.paidSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.placeDemoOrder;
import static it.gerio.platform.acceptance.Giro011Webhooks.sendOk;
import static it.gerio.platform.acceptance.Giro014Data.body;
import static it.gerio.platform.acceptance.Giro014Data.main;
import static it.gerio.platform.acceptance.Giro014Data.text;
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
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 014: the order status page {@code GET /ordine/{token}} reached through the secret link, and
 * the link «Segui il tuo ordine» on the outcome page of a paid order (docs/lab/giri/014-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro014OrderStatusAcceptanceTest {

  private static final Instant T0 = Giro010CheckoutAcceptanceTest.T0;

  /** 00:30 of 12/10/2026 in Rome, still 11/10 in UTC: the order date must be the shop's. */
  private static final Instant LATE_EVENING_UTC = Instant.parse("2026-10-11T22:30:00Z");

  private static final String SHOP_NAME = "Fioreria Il Glicine";
  private static final String ROSES = "Mazzo di rose rosse — 12 rose";
  private static final String ORCHID = "Orchidea Phalaenopsis bianca — Vaso 12 cm";
  private static final Pattern FOLLOW_LINK =
      Pattern.compile(
          "<a[^>]*href=[\"']/ordine/([A-Za-z0-9_-]{43})[\"'][^>]*>\\s*Segui il tuo ordine");
  private static final Pattern BACK_TO_CART =
      Pattern.compile("<a[^>]*href=[\"']/carrello[\"'][^>]*>\\s*Torna al carrello");
  private static final Pattern ORDER_LINK_NOT_OUTCOME =
      Pattern.compile("href=[\"']/ordine/(?!esito)");

  @Inject DataSource dataSource;

  private Giro014Data data;
  private Giro010FakeStripe stripe;
  private final TestClock clock = new TestClock(T0);

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

  private static Response get(String host, String path) {
    return given().redirects().follow(false).header("Host", host).when().get(path);
  }

  private static String statusPage(String token, String what) {
    Response response = get(DEMO, "/ordine/" + token);
    assertEquals(200, response.statusCode(), what + ": GET /ordine/<token>; " + body(response));
    return body(response);
  }

  private static void assertNoPersonalData(String html, String what) {
    assertFalse(html.contains("Giulia"), what + ": shows the customer's name");
    assertFalse(html.contains(Giro010Checkout.EMAIL), what + ": shows the customer's email");
    assertFalse(html.contains("333 000 1234"), what + ": shows the customer's phone");
  }

  private Placed paidDemoOrder() throws Exception {
    Placed order = placeDemoOrder(data.giro011.checkout, stripe);
    sendOk(
        event(
            "evt_giro014_paid_1",
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(order, order.sessionId(), "pi_giro014_demo_1")),
        "paid");
    return order;
  }

  @Test
  void thePaidOutcomeLinksToTheStatusPageWithNumberStatusDatesLinesTotalAndContacts()
      throws Exception {
    clock.set(LATE_EVENING_UTC);
    Placed order = paidDemoOrder();

    Response outcome = get(DEMO, "/ordine/esito?session_id=" + order.sessionId());
    assertEquals(200, outcome.statusCode(), "outcome page");
    Matcher link = FOLLOW_LINK.matcher(body(outcome));
    assertTrue(
        link.find(), "the paid outcome has no link «Segui il tuo ordine» to /ordine/<token>");
    String token = link.group(1);
    assertEquals(data.token(order.orderId()), token, "the link is not the order's public_token");

    Response response = get(DEMO, "/ordine/" + token);
    assertEquals(200, response.statusCode(), "status page; " + body(response));
    String html = body(response);
    String text = text(html);

    assertTrue(text.contains("Ordine 2026-0001"), "no «Ordine 2026-0001»");
    assertTrue(text.contains(SHOP_NAME), "no shop name «" + SHOP_NAME + "»");
    assertTrue(text.contains("Pagato"), "no status «Pagato»");
    assertTrue(text.contains("12/10/2026"), "no order date 12/10/2026 (shop's time zone)");
    assertFalse(text.contains("11/10/2026"), "order date in UTC instead of Europe/Rome");
    assertTrue(text.contains("Ritiro in negozio"), "no «Ritiro in negozio»");
    assertTrue(text.contains("13/10/2026"), "no pickup date 13/10/2026");

    int roses = text.indexOf(ROSES);
    int orchid = text.indexOf(ORCHID);
    int total = text.lastIndexOf("104,80" + NBSP + "€");
    assertTrue(roses >= 0, "no line «" + ROSES + "»");
    assertTrue(orchid >= 0, "no line «" + ORCHID + "»");
    assertTrue(total >= 0, "no total 104,80 €");
    assertTrue(roses < orchid && orchid < total, "lines not in order roses, orchid, total");
    String rosesLine = text.substring(roses + ROSES.length(), orchid);
    String orchidLine = text.substring(orchid + ORCHID.length(), total);
    assertTrue(rosesLine.matches("(?s).*(?<![\\d,])1(?![\\d,]).*"), "roses: no quantity 1");
    assertTrue(rosesLine.contains("45,00" + NBSP + "€"), "roses: no 45,00 €");
    assertTrue(orchidLine.matches("(?s).*(?<![\\d,])2(?![\\d,]).*"), "orchid: no quantity 2");
    assertTrue(orchidLine.contains("29,90" + NBSP + "€"), "orchid: no unit price 29,90 €");
    assertTrue(orchidLine.contains("59,80" + NBSP + "€"), "orchid: no line total 59,80 €");

    assertTrue(text.contains("+39 0583 000000"), "no shop phone");
    assertTrue(text.contains("ciao@glicine.example"), "no shop email");
    assertNoPersonalData(html, "paid status page");
    assertFalse(BACK_TO_CART.matcher(html).find(), "a paid order links back to the cart");
  }

  @Test
  void theStatusPageIsNotCachedNotIndexedAndSendsNoReferrer() throws Exception {
    Placed order = paidDemoOrder();
    Response response = get(DEMO, "/ordine/" + data.token(order.orderId()));
    assertEquals(200, response.statusCode(), "status page; " + body(response));
    assertEquals("no-store", response.getHeader("Cache-Control"), "Cache-Control");
    assertEquals("no-referrer", response.getHeader("Referrer-Policy"), "Referrer-Policy");
    assertEquals("noindex", response.getHeader("X-Robots-Tag"), "X-Robots-Tag");
  }

  @Test
  void aPendingOrderSaysWaitingWithoutCartLinkAndItsOutcomeHasNoStatusLink() throws Exception {
    Placed order = placeDemoOrder(data.giro011.checkout, stripe);

    String html = statusPage(data.token(order.orderId()), "pending");
    assertTrue(text(html).contains("In attesa di pagamento"), "no «In attesa di pagamento»");
    assertTrue(text(html).contains("Ordine 2026-0001"), "pending: no «Ordine 2026-0001»");
    assertFalse(html.contains("Torna al carrello"), "pending: link «Torna al carrello»");
    assertFalse(
        main(html).matches("(?s).*href=[\"']/carrello[\"'].*"), "pending: link to /carrello");
    assertNoPersonalData(html, "pending status page");

    Response outcome = get(DEMO, "/ordine/esito?session_id=" + order.sessionId());
    assertEquals(200, outcome.statusCode(), "pending outcome page");
    String outcomeHtml = body(outcome);
    assertTrue(
        outcomeHtml.contains("Stiamo confermando il pagamento"),
        "pending outcome: control text missing");
    assertFalse(
        ORDER_LINK_NOT_OUTCOME.matcher(outcomeHtml).find(),
        "pending outcome links to an /ordine/ page other than /ordine/esito");
    assertFalse(outcomeHtml.contains("Segui il tuo ordine"), "pending outcome: «Segui»");
  }

  @Test
  void anExpiredOrderSaysNotCompletedWithALinkBackToTheCart() throws Exception {
    Placed order = placeDemoOrder(data.giro011.checkout, stripe);
    sendOk(
        event(
            "evt_giro014_expired_1",
            EXPIRED,
            DEMO_ACCOUNT,
            expiredSession(order, order.sessionId())),
        "expired");

    String html = statusPage(data.token(order.orderId()), "expired");
    assertTrue(text(html).contains("Pagamento non completato"), "no «Pagamento non completato»");
    assertTrue(
        BACK_TO_CART.matcher(main(html)).find(),
        "no link «Torna al carrello» to /carrello inside <main>");
    assertNoPersonalData(html, "expired status page");
  }
}
