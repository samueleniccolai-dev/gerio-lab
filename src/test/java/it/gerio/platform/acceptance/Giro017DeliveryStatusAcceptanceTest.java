package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.paidSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.sendOk;
import static it.gerio.platform.acceptance.Giro017Checkout.ADDRESS;
import static it.gerio.platform.acceptance.Giro017Checkout.NOTES;
import static it.gerio.platform.acceptance.Giro017Checkout.RECIPIENT;
import static it.gerio.platform.acceptance.Giro017Checkout.RECIPIENT_PHONE;
import static it.gerio.platform.acceptance.Giro017Checkout.delivery;
import static it.gerio.platform.acceptance.Giro017Checkout.eur;
import static it.gerio.platform.acceptance.Giro017Checkout.roses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import it.gerio.platform.acceptance.Giro011Webhooks.Placed;
import it.gerio.platform.acceptance.Giro015Mail.Sent;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 017 (goal T7): a paid delivery order shows its type and fee on the status page and in the
 * confirmation mail; the status page shows no recipient, address or notes (giro 014: no personal
 * data behind the link). The invented address «Via delle Prove 7» appears nowhere else.
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro017DeliveryStatusAcceptanceTest {

  static final Instant T0 = Instant.parse("2026-10-20T08:00:00Z");
  static final String TOMORROW = "2026-10-21";

  @Inject DataSource dataSource;

  private Giro017Data data;
  private Giro010FakeStripe stripe;
  private final TestClock clock = new TestClock(T0);

  @BeforeEach
  void setUp() throws Exception {
    QuarkusMock.installMockForType(clock, Clock.class);
    stripe = Giro010FakeStripe.get();
    stripe.reset();
    data = new Giro017Data(dataSource);
    Giro015Mail.clear();
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  /** 12 roses delivered to Anna Verdi, Via delle Prove 7, Lucca centro; then paid. */
  private Map<String, Object> paidDelivery() throws SQLException {
    Map<String, Object> order = paid(delivery(TOMORROW, "55100", "Lucca"));
    assertEquals("DELIVERY", order.get("fulfillment_type"), "the order is not a delivery");
    return order;
  }

  /** 12 roses ordered with the given form, then paid through the webhook. */
  private Map<String, Object> paid(Map<String, String> form) throws SQLException {
    Giro008Browser browser = roses(data.checkout());
    Response response = browser.post("/checkout", form);
    assertEquals(
        303, response.statusCode(), "POST /checkout; body: " + Giro008Browser.body(response));
    Map<String, Object> order = data.onlyDemoOrder();
    Placed placed =
        new Placed(
            browser,
            ((Number) order.get("id")).longValue(),
            (String) order.get("number"),
            stripe.createdSessions().get(0));
    sendOk(
        event(
            "evt_giro017_paid_1",
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(placed, placed.sessionId(), "pi_g17")),
        "paid");
    return data.orders(DEMO_ID).get(0);
  }

  private static boolean hasLooseAmount(String text, String amount) {
    return text.contains(eur(amount)) || text.contains(amount + " €");
  }

  @Test
  void theStatusPageShowsDeliveryAndFeeButNoPersonalData() throws SQLException {
    Map<String, Object> order = paidDelivery();
    assertEquals("PAID", order.get("status"), "order status");

    Response page =
        given()
            .redirects()
            .follow(false)
            .header("Host", DEMO)
            .when()
            .get("/ordine/" + order.get("public_token"));
    assertEquals(200, page.statusCode(), "status page");
    String html = Giro017Checkout.decoded(page);
    String text = Giro014Data.text(Giro014Data.main(html));

    assertTrue(
        text.contains("Consegna a domicilio: 21/10/2026"), "no «Consegna a domicilio»: " + text);
    assertTrue(text.contains("Consegna: " + eur("5,00")), "no «Consegna: 5,00 €»: " + text);
    assertTrue(text.contains(eur("50,00")), "no total 50,00 €: " + text);
    assertFalse(text.contains("Ritiro in negozio"), "a delivery shown as a pickup: " + text);
    for (String personal : List.of(RECIPIENT, RECIPIENT_PHONE, ADDRESS, "Via delle Prove", NOTES)) {
      assertFalse(html.contains(personal), "the status page shows «" + personal + "»");
    }
  }

  @Test
  void theConfirmationMailShowsDeliveryAndFee() throws SQLException {
    paidDelivery();
    Giro015Mail.drainOnce();

    List<Sent> mails = Giro015Mail.sentTo(Giro010Checkout.EMAIL);
    assertEquals(1, mails.size(), "mails to the customer");
    Sent mail = mails.get(0);
    for (String part : List.of("html", "text")) {
      String text = "html".equals(part) ? Giro015Mail.text(mail.html()) : mail.text();
      String what = part + " part: ";
      assertTrue(text.contains("Consegna a domicilio"), what + "no «Consegna a domicilio»");
      assertTrue(text.contains("21/10/2026"), what + "no delivery date 21/10/2026");
      assertTrue(hasLooseAmount(text, "Consegna: 5,00"), what + "no «Consegna: 5,00 €»: " + text);
      assertTrue(hasLooseAmount(text, "50,00"), what + "no total 50,00 €");
      assertFalse(text.contains("Ritiro in negozio"), what + "a delivery shown as a pickup");
    }
  }

  @Test
  void aPickupStillShowsRitiroInNegozioAndNoFee() throws Exception {
    // Non-regression: a pickup keeps the page of giro 014.
    Object token = paid(Giro010Checkout.form(TOMORROW)).get("public_token");
    Response page =
        given().redirects().follow(false).header("Host", DEMO).when().get("/ordine/" + token);
    assertEquals(200, page.statusCode(), "status page of a pickup");
    String text = Giro014Data.text(Giro014Data.main(Giro017Checkout.decoded(page)));
    assertTrue(text.contains("Ritiro in negozio"), "a pickup without «Ritiro in negozio»");
    assertFalse(text.contains("Consegna a domicilio"), "a pickup shown as a delivery");
    assertFalse(text.contains("Consegna: "), "a pickup with a delivery fee");
  }
}
