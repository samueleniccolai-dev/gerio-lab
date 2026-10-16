package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro005DemoData.MARCO_ID;
import static it.gerio.platform.acceptance.Giro008Browser.body;
import static it.gerio.platform.acceptance.Giro010Checkout.lineItems;
import static it.gerio.platform.acceptance.Giro010CheckoutData.MARCO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro017Checkout.CUTOFF;
import static it.gerio.platform.acceptance.Giro017Checkout.OUT_OF_ZONE;
import static it.gerio.platform.acceptance.Giro017Checkout.assertDeliveryLine;
import static it.gerio.platform.acceptance.Giro017Checkout.assertRejected;
import static it.gerio.platform.acceptance.Giro017Checkout.decoded;
import static it.gerio.platform.acceptance.Giro017Checkout.delivery;
import static it.gerio.platform.acceptance.Giro017Checkout.hasAmount;
import static it.gerio.platform.acceptance.Giro017Checkout.pickup;
import static it.gerio.platform.acceptance.Giro017Checkout.roses;
import static it.gerio.platform.acceptance.Giro017Data.CAPANNORI;
import static it.gerio.platform.acceptance.Giro017Data.LUCCA;
import static it.gerio.platform.acceptance.Giro017Data.MILANO;
import static it.gerio.platform.acceptance.Giro017Data.VERSILIA;
import static it.gerio.platform.acceptance.Giro017Data.cents;
import static it.gerio.platform.acceptance.Giro017Data.column;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 017 (goal T7): zones and cutoff belong to one shop. Marco's shop has «Milano centro» (CAP
 * 20121, Milano, 7,00 €, no minimum) and no orderCutoff; the demo has its seeded zones and the
 * cutoff at 12:00 (docs/lab/giri/017-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro017DeliveryIsolationAcceptanceTest {

  static final Instant T0 = Instant.parse("2026-10-20T08:00:00Z");
  static final Instant NOON = Instant.parse("2026-10-20T10:00:00Z"); // 12:00 in Rome
  static final String TODAY = "2026-10-20";
  static final String TOMORROW = "2026-10-21";
  static final String MARCO_PHONE = "+39 055 000000";

  @Inject DataSource dataSource;

  private Giro017Data data;
  private Giro010FakeStripe stripe;
  private final TestClock clock = new TestClock(T0);
  private long marcoZone;

  @BeforeEach
  void setUp() throws Exception {
    QuarkusMock.installMockForType(clock, Clock.class);
    stripe = Giro010FakeStripe.get();
    stripe.reset();
    data = new Giro017Data(dataSource);
    marcoZone = data.insertZone(MARCO_ID, MILANO, "20121", "Milano", 700, 0);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  @Test
  void eachCheckoutShowsOnlyItsOwnZones() {
    String marco = decoded(Giro017Checkout.marco(data.checkout()).get("/checkout"));
    assertTrue(marco.contains(MILANO), "Marco's checkout without «" + MILANO + "»");
    assertTrue(hasAmount(marco, "7,00"), "Marco's checkout without the fee 7,00 €");
    for (String demoZone : List.of(LUCCA, CAPANNORI, VERSILIA)) {
      assertFalse(marco.contains(demoZone), "Marco's checkout shows the demo zone " + demoZone);
    }

    String demo = decoded(roses(data.checkout()).get("/checkout"));
    assertTrue(demo.contains(LUCCA), "demo checkout without «" + LUCCA + "»");
    assertFalse(demo.contains(MILANO), "the demo checkout shows Marco's zone");
  }

  @Test
  void anAddressOfTheOtherShopIsOutOfZoneOnBothSides() throws SQLException {
    Giro008Browser demo = roses(data.checkout());
    assertRejected(
        demo.post("/checkout", delivery(TOMORROW, "20121", "Milano")),
        OUT_OF_ZONE,
        "demo, Marco's 20121 Milano");

    Giro008Browser marco = Giro017Checkout.marco(data.checkout());
    String html =
        assertRejected(
            marco.post("/checkout", delivery(TOMORROW, "55100", "Lucca")),
            OUT_OF_ZONE,
            "Marco, the demo's 55100 Lucca");
    assertFalse(html.contains("wa.me/393330000000"), "Marco's page shows the demo's WhatsApp");
    assertTrue(html.contains(MARCO_PHONE), "Marco without WhatsApp must show his phone");
    assertEquals(0, data.countOrders(), "orders created");
    assertEquals(0, stripe.allCalls().size(), "Stripe was called");
  }

  @Test
  void aZoneIdOfTheOtherShopInTheFormChangesNothing() throws SQLException {
    Giro008Browser demo = roses(data.checkout());
    Map<String, String> milan = new LinkedHashMap<>(delivery(TOMORROW, "20121", "Milano"));
    milan.put("zoneId", Long.toString(marcoZone));
    assertRejected(demo.post("/checkout", milan), OUT_OF_ZONE, "demo with Marco's zoneId");
    assertEquals(0, data.countOrders(), "orders with Marco's zoneId on the demo");

    Map<String, String> lucca = new LinkedHashMap<>(delivery(TOMORROW, "55100", "Lucca"));
    lucca.put("zoneId", Long.toString(marcoZone));
    Response response = demo.post("/checkout", lucca);
    assertEquals(
        303, response.statusCode(), "demo Lucca + Marco's zoneId; body: " + body(response));
    Map<String, Object> order = data.onlyDemoOrder();
    assertEquals(
        data.zoneId(DEMO_ID, LUCCA), ((Number) column(order, "zone_id")).longValue(), "zone");
    assertEquals(500L, cents(order, "delivery_fee_cents"), "fee");
  }

  @Test
  void aDeliveryInMarcosZoneGoesToMarcosAccountWithHisFee() throws SQLException {
    Giro008Browser marco = Giro017Checkout.marco(data.checkout());

    Response response = marco.post("/checkout", delivery(TOMORROW, "20121", "Milano"));

    assertEquals(303, response.statusCode(), "Marco 20121 Milano; body: " + body(response));
    List<Map<String, Object>> orders = data.orders(MARCO_ID);
    assertEquals(1, orders.size(), "Marco's orders");
    assertEquals(0, data.orders(DEMO_ID).size(), "demo orders");
    Map<String, Object> order = orders.get(0);
    assertEquals("DELIVERY", order.get("fulfillment_type"), "fulfillment type");
    assertEquals(marcoZone, ((Number) column(order, "zone_id")).longValue(), "zone_id");
    assertEquals(700L, cents(order, "delivery_fee_cents"), "fee");
    assertEquals(3700L, cents(order, "total_cents"), "total");
    Giro010FakeStripe.Call call = stripe.sessionCalls().get(0);
    assertEquals(MARCO_ACCOUNT, call.header("Stripe-Account"), "Stripe-Account");
    assertDeliveryLine(lineItems(call.form()), MILANO, 700);
  }

  @Test
  void theCutoffOfTheDemoDoesNotApplyToMarco() throws SQLException {
    clock.set(NOON);
    Giro008Browser demo = roses(data.checkout());
    assertRejected(demo.post("/checkout", pickup(TODAY)), CUTOFF, "demo today at 12:00");
    assertEquals(0, data.countOrders(), "demo order after its cutoff");

    // Non-regression: a shop without orderCutoff, or with one that is not HH:mm, has no limit.
    Giro008Browser marco = Giro017Checkout.marco(data.checkout());
    Response noCutoff = marco.post("/checkout", pickup(TODAY));
    assertEquals(303, noCutoff.statusCode(), "Marco today at 12:00; body: " + body(noCutoff));

    data.setSettings(MARCO_ID, "{\"orderCutoff\": \"mezzogiorno\"}");
    Giro008Browser again = Giro017Checkout.marco(data.checkout());
    Response malformed = again.post("/checkout", pickup(TODAY));
    assertEquals(303, malformed.statusCode(), "malformed orderCutoff; body: " + body(malformed));
    assertEquals(2, data.orders(MARCO_ID).size(), "Marco's orders");
  }
}
