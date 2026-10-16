package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro008Browser.body;
import static it.gerio.platform.acceptance.Giro010Checkout.assertLine;
import static it.gerio.platform.acceptance.Giro010Checkout.assertToStripe;
import static it.gerio.platform.acceptance.Giro010Checkout.lineItems;
import static it.gerio.platform.acceptance.Giro010Checkout.with;
import static it.gerio.platform.acceptance.Giro017Checkout.ADDRESS;
import static it.gerio.platform.acceptance.Giro017Checkout.BAD_POSTAL_CODE;
import static it.gerio.platform.acceptance.Giro017Checkout.MISSING_ADDRESS;
import static it.gerio.platform.acceptance.Giro017Checkout.MISSING_CITY;
import static it.gerio.platform.acceptance.Giro017Checkout.MISSING_RECIPIENT;
import static it.gerio.platform.acceptance.Giro017Checkout.MISSING_RECIPIENT_PHONE;
import static it.gerio.platform.acceptance.Giro017Checkout.NOTES;
import static it.gerio.platform.acceptance.Giro017Checkout.OUT_OF_ZONE;
import static it.gerio.platform.acceptance.Giro017Checkout.RECIPIENT;
import static it.gerio.platform.acceptance.Giro017Checkout.RECIPIENT_PHONE;
import static it.gerio.platform.acceptance.Giro017Checkout.WHATSAPP_LINK;
import static it.gerio.platform.acceptance.Giro017Checkout.WHATSAPP_TEXT;
import static it.gerio.platform.acceptance.Giro017Checkout.assertDeliveryLine;
import static it.gerio.platform.acceptance.Giro017Checkout.assertRejected;
import static it.gerio.platform.acceptance.Giro017Checkout.decoded;
import static it.gerio.platform.acceptance.Giro017Checkout.delivery;
import static it.gerio.platform.acceptance.Giro017Checkout.hasAmount;
import static it.gerio.platform.acceptance.Giro017Checkout.minimum;
import static it.gerio.platform.acceptance.Giro017Checkout.roses;
import static it.gerio.platform.acceptance.Giro017Data.CAPANNORI;
import static it.gerio.platform.acceptance.Giro017Data.LUCCA;
import static it.gerio.platform.acceptance.Giro017Data.VERSILIA;
import static it.gerio.platform.acceptance.Giro017Data.cents;
import static it.gerio.platform.acceptance.Giro017Data.column;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 017 (goal T7): pickup or home delivery at the checkout of the demo shop, with the active
 * zones, their fee and minimum order computed on the server (docs/lab/giri/017-piano.md). Clock:
 * Tuesday 20/10/2026 10:00 in Rome, before the cutoff; delivery on 21/10/2026.
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro017DeliveryZonesAcceptanceTest {

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
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  @Test
  void theCheckoutOffersPickupAndDeliveryWithTheActiveZonesOnly() {
    Giro008Browser browser = roses(data.checkout());
    String html = decoded(browser.get("/checkout"));

    assertTrue(html.contains("Ritiro in negozio"), "no «Ritiro in negozio»");
    assertTrue(html.contains("Consegna a domicilio"), "no «Consegna a domicilio»");
    assertTrue(html.contains(LUCCA), "no zone «" + LUCCA + "»");
    assertTrue(hasAmount(html, "5,00"), "no fee 5,00 € of " + LUCCA);
    assertTrue(hasAmount(html, "25,00"), "no minimum 25,00 € of " + LUCCA);
    assertTrue(html.contains(CAPANNORI), "no zone «" + CAPANNORI + "»");
    assertTrue(hasAmount(html, "9,00"), "no fee 9,00 € of " + CAPANNORI);
    assertTrue(hasAmount(html, "35,00"), "no minimum 35,00 € of " + CAPANNORI);
    assertFalse(html.contains(VERSILIA), "the inactive zone «" + VERSILIA + "» is shown");

    for (String value : List.of("PICKUP", "DELIVERY")) {
      assertTrue(
          Pattern.compile(
                  "<input[^>]*name=[\"']fulfillmentType[\"'][^>]*value=[\"']"
                      + value
                      + "[\"']|"
                      + "<input[^>]*value=[\"']"
                      + value
                      + "[\"'][^>]*name=[\"']fulfillmentType[\"']")
              .matcher(html)
              .find(),
          "no <input name=fulfillmentType value=" + value + ">");
    }
    for (String field :
        List.of("recipientName", "recipientPhone", "addressLine", "postalCode", "city")) {
      assertTrue(
          Pattern.compile("<input[^>]*name=[\"']" + field + "[\"']").matcher(html).find(),
          "no input " + field);
    }
    assertTrue(
        Pattern.compile("<(input|textarea)[^>]*name=[\"']deliveryNotes[\"']").matcher(html).find(),
        "no field deliveryNotes");
  }

  @Test
  void aDeliveryInLuccaCentroAddsTheZoneFeeToOrderVatAndStripe() throws SQLException {
    Giro008Browser browser = roses(data.checkout());

    Response response = browser.post("/checkout", delivery(TOMORROW, "55100", "Lucca"));

    assertEquals(1, stripe.createdSessions().size(), "sessions created");
    assertToStripe(response, stripe.createdSessions().get(0), "POST delivery to Lucca centro");
    Map<String, Object> order = data.onlyDemoOrder();
    long orderId = ((Number) order.get("id")).longValue();
    assertEquals("DELIVERY", order.get("fulfillment_type"), "fulfillment type");
    assertEquals(TOMORROW, String.valueOf(order.get("delivery_date")), "delivery date");
    assertEquals(4500L, cents(order, "subtotal_cents"), "subtotal");
    assertEquals(500L, cents(order, "delivery_fee_cents"), "delivery fee");
    assertEquals(5000L, cents(order, "total_cents"), "total");
    assertEquals(
        data.zoneId(DEMO_ID, LUCCA), ((Number) column(order, "zone_id")).longValue(), "zone_id");
    assertEquals(RECIPIENT, column(order, "recipient_name"), "recipient_name");
    assertEquals(RECIPIENT_PHONE, column(order, "recipient_phone"), "recipient_phone");
    assertEquals(ADDRESS, column(order, "address_line"), "address_line");
    assertEquals("55100", column(order, "postal_code"), "postal_code");
    assertEquals("Lucca", column(order, "city"), "city");
    assertEquals(NOTES, column(order, "delivery_notes"), "delivery_notes");

    // The fee is part of what the customer pays: it enters the VAT breakdown (rate of the roses).
    assertEquals(5000L, data.vatGross(orderId), "taxable + tax of vat_breakdown");
    assertEquals(List.of(1000), data.vatRates(orderId), "VAT rates of the order");

    List<Map<String, String>> lines = lineItems(stripe.sessionCalls().get(0).form());
    assertEquals(2, lines.size(), "Stripe line items: " + lines);
    assertLine(lines, "Mazzo di rose rosse", "12 rose", 4500, 1);
    assertDeliveryLine(lines, LUCCA, 500);
  }

  @Test
  void thePostalCodeWinsAndTheMunicipalityIgnoresCaseAndSpaces() throws SQLException {
    // Unknown postal code, municipality with spaces and lower case: Capannori e Porcari.
    Giro008Browser byCity = roses(data.checkout());
    Response first = byCity.post("/checkout", delivery(TOMORROW, "55010", " capannori "));
    assertToStripe(first, stripe.createdSessions().get(0), "55010 + « capannori »");
    Map<String, Object> order = data.onlyDemoOrder();
    assertEquals(
        data.zoneId(DEMO_ID, CAPANNORI),
        ((Number) column(order, "zone_id")).longValue(),
        "zone found by municipality");
    assertEquals(900L, cents(order, "delivery_fee_cents"), "fee of " + CAPANNORI);
    assertEquals(5400L, cents(order, "total_cents"), "total");

    // A postal code of Capannori with the municipality of Lucca: the postal code wins.
    Giro008Browser byCode = roses(data.checkout());
    Response second = byCode.post("/checkout", delivery(TOMORROW, "55012", "Lucca"));
    assertEquals(303, second.statusCode(), "55012 + Lucca: status; body: " + body(second));
    List<Map<String, Object>> orders = data.orders(DEMO_ID);
    assertEquals(2, orders.size(), "orders of the demo");
    assertEquals(
        data.zoneId(DEMO_ID, CAPANNORI),
        ((Number) column(orders.get(1), "zone_id")).longValue(),
        "zone found by postal code first");
    assertEquals(900L, cents(orders.get(1), "delivery_fee_cents"), "fee by postal code");
  }

  @Test
  void anAddressOutsideTheActiveZonesIsRefusedWithTheWhatsAppLink() throws SQLException {
    Giro008Browser browser = roses(data.checkout());

    Map<String, Map<String, String>> cases = new LinkedHashMap<>();
    cases.put("Versilia, inactive zone", delivery(TOMORROW, "55049", "Viareggio"));
    cases.put("Pisa, no zone", delivery(TOMORROW, "56100", "Pisa"));
    for (Map.Entry<String, Map<String, String>> c : cases.entrySet()) {
      String html =
          assertRejected(browser.post("/checkout", c.getValue()), OUT_OF_ZONE, c.getKey());
      assertTrue(
          html.contains("href=\"" + WHATSAPP_LINK + "\""),
          c.getKey() + ": no link " + WHATSAPP_LINK);
      assertTrue(html.contains(WHATSAPP_TEXT), c.getKey() + ": no «" + WHATSAPP_TEXT + "»");
      assertFalse(
          html.contains(WHATSAPP_LINK + "?text=Anna"), c.getKey() + ": customer data in the link");
      assertEquals(0, data.countOrders(), c.getKey() + ": an order was created");
      assertEquals(0, stripe.allCalls().size(), c.getKey() + ": Stripe was called");
    }
  }

  @Test
  void theMinimumIsOnTheProductsOnlyAndTheBoundaryIsAccepted() throws SQLException {
    // Orchid 2990 + fee 900 = 3890 >= 3500, but the products alone are below the minimum.
    Giro008Browser orchid = Giro017Checkout.orchid(data.checkout());
    assertRejected(
        orchid.post("/checkout", delivery(TOMORROW, "55012", "Capannori")),
        minimum(CAPANNORI, "35,00"),
        "orchid to Capannori");
    assertEquals(0, data.countOrders(), "an order below the minimum was created");
    assertEquals(0, stripe.allCalls().size(), "Stripe was called below the minimum");

    // Seasonal bouquet «Medio» 3500 = the minimum: accepted.
    Giro008Browser medium = Giro017Checkout.medium(data.checkout());
    Response response = medium.post("/checkout", delivery(TOMORROW, "55012", "Capannori"));
    assertToStripe(response, stripe.createdSessions().get(0), "3500 to Capannori");
    Map<String, Object> order = data.onlyDemoOrder();
    assertEquals(900L, cents(order, "delivery_fee_cents"), "fee");
    assertEquals(4400L, cents(order, "total_cents"), "total");
  }

  @Test
  void missingDeliveryFieldsGiveOneErrorEachAndNoOrder() throws SQLException {
    Giro008Browser browser = roses(data.checkout());
    Map<String, String> empty = delivery(TOMORROW, "", "");
    for (String field : List.of("recipientName", "recipientPhone", "addressLine")) {
      empty.put(field, " ");
    }
    String html = decoded(browser.post("/checkout", empty));
    for (String message :
        List.of(
            MISSING_RECIPIENT,
            MISSING_RECIPIENT_PHONE,
            MISSING_ADDRESS,
            BAD_POSTAL_CODE,
            MISSING_CITY)) {
      assertTrue(html.contains(message), "no «" + message + "»");
    }
    assertRejected(
        browser.post("/checkout", delivery(TOMORROW, "5510", "Lucca")),
        BAD_POSTAL_CODE,
        "postal code of 4 digits");
    assertRejected(
        browser.post(
            "/checkout",
            with(delivery(TOMORROW, "55100", "Lucca"), "deliveryNotes", "x".repeat(201))),
        "action=\"/checkout\"",
        "notes longer than 200");
    assertEquals(0, data.countOrders(), "an order was created");
    assertEquals(0, stripe.allCalls().size(), "Stripe was called");
  }

  @Test
  void anUnknownFulfillmentTypeIsRefused() throws SQLException {
    Giro008Browser browser = roses(data.checkout());
    for (String type : List.of("CEMETERY", "xyz")) {
      Response response =
          browser.post(
              "/checkout", with(delivery(TOMORROW, "55100", "Lucca"), "fulfillmentType", type));
      assertEquals(400, response.statusCode(), type + ": status; body: " + body(response));
      assertEquals(0, data.countOrders(), type + ": an order was created");
      assertEquals(0, stripe.allCalls().size(), type + ": Stripe was called");
    }
  }

  @Test
  void aPickupWithoutFulfillmentTypeIsStillAPickupWithoutFee() throws SQLException {
    Giro008Browser browser = roses(data.checkout());

    Response response = browser.post("/checkout", Giro010Checkout.form(TOMORROW));

    assertToStripe(response, stripe.createdSessions().get(0), "POST without fulfillmentType");
    Map<String, Object> order = data.onlyDemoOrder();
    assertEquals("PICKUP", order.get("fulfillment_type"), "fulfillment type");
    assertEquals(0L, cents(order, "delivery_fee_cents"), "fee");
    assertEquals(4500L, cents(order, "total_cents"), "total");
    assertNull(column(order, "zone_id"), "zone_id of a pickup");
    assertNull(column(order, "address_line"), "address_line of a pickup");
    List<Map<String, String>> lines = lineItems(stripe.sessionCalls().get(0).form());
    assertEquals(1, lines.size(), "a pickup has no delivery line: " + lines);
  }

  @Test
  void zoneAndFeeSentByTheBrowserAreIgnored() throws SQLException {
    long capannori = data.zoneId(DEMO_ID, CAPANNORI);
    Giro008Browser browser = roses(data.checkout());
    Map<String, String> forged = new LinkedHashMap<>(delivery(TOMORROW, "55100", "Lucca"));
    forged.put("zoneId", Long.toString(capannori));
    forged.put("deliveryFeeCents", "0");
    forged.put("delivery_fee_cents", "1");
    forged.put("total_cents", "1");

    Response response = browser.post("/checkout", forged);

    assertToStripe(response, stripe.createdSessions().get(0), "POST with forged zone and fee");
    Map<String, Object> order = data.onlyDemoOrder();
    assertEquals(
        data.zoneId(DEMO_ID, LUCCA), ((Number) column(order, "zone_id")).longValue(), "zone");
    assertEquals(500L, cents(order, "delivery_fee_cents"), "fee");
    assertEquals(5000L, cents(order, "total_cents"), "total");
    assertDeliveryLine(lineItems(stripe.sessionCalls().get(0).form()), LUCCA, 500);
  }

  @Test
  void aZoneChangedBetweenPageAndPostCountsAsItIsAtThePost() throws SQLException {
    long lucca = data.zoneId(DEMO_ID, LUCCA);
    Giro008Browser browser = roses(data.checkout());
    assertTrue(hasAmount(decoded(browser.get("/checkout")), "5,00"), "page before the change");

    data.setZoneFee(lucca, 600);
    Response response = browser.post("/checkout", delivery(TOMORROW, "55100", "Lucca"));
    assertToStripe(response, stripe.createdSessions().get(0), "POST after the fee changed");
    Map<String, Object> order = data.onlyDemoOrder();
    assertEquals(600L, cents(order, "delivery_fee_cents"), "fee read at the POST");
    assertEquals(5100L, cents(order, "total_cents"), "total");
    assertDeliveryLine(lineItems(stripe.sessionCalls().get(0).form()), LUCCA, 600);

    Giro008Browser late = roses(data.checkout());
    data.setZoneActive(lucca, false);
    assertRejected(
        late.post("/checkout", delivery(TOMORROW, "55100", "Lucca")),
        OUT_OF_ZONE,
        "POST after the zone was switched off");
    assertEquals(1, data.countOrders(), "orders after the zone was switched off");
  }

  @Test
  void theSameDataReusesTheOrderAndAnyDeliveryChangeMakesANewOne() throws SQLException {
    Giro008Browser browser = roses(data.checkout());
    Map<String, String> first = delivery(TOMORROW, "55100", "Lucca");

    assertEquals(303, browser.post("/checkout", first).statusCode(), "first POST");
    assertEquals(303, browser.post("/checkout", first).statusCode(), "same POST again");
    assertEquals(1, data.countOrders(), "the same data must reuse the pending order");
    assertEquals(1, stripe.createdSessions().size(), "sessions for the same data");

    Map<String, Map<String, String>> changes = new LinkedHashMap<>();
    changes.put("recipientPhone", with(first, "recipientPhone", "+39 333 999 0008"));
    changes.put("recipientName", with(first, "recipientName", "Anna Neri"));
    changes.put("addressLine", with(first, "addressLine", "Via delle Prove 9"));
    changes.put("deliveryNotes", with(first, "deliveryNotes", "Lasciare al vicino"));
    changes.put("postalCode", delivery(TOMORROW, "55012", "Capannori"));
    int expected = 1;
    for (Map.Entry<String, Map<String, String>> c : changes.entrySet()) {
      Response response = browser.post("/checkout", c.getValue());
      expected++;
      assertEquals(303, response.statusCode(), c.getKey() + ": status; body: " + body(response));
      assertEquals(expected, data.countOrders(), c.getKey() + " changed: no new order");
      assertEquals(expected, stripe.createdSessions().size(), c.getKey() + ": sessions");
    }
    List<Map<String, Object>> orders = data.orders(DEMO_ID);
    assertEquals("+39 333 999 0008", column(orders.get(1), "recipient_phone"), "second phone");
    assertEquals(900L, cents(orders.get(orders.size() - 1), "delivery_fee_cents"), "last fee");
  }

  @Test
  void twoZonesWithTheSamePostalCodeAlwaysGiveTheOlderOne() throws SQLException {
    data.insertZone(DEMO_ID, "Lucca centro bis", "55100", "Lucca", 800, 0);
    Giro008Browser browser = roses(data.checkout());

    Response response = browser.post("/checkout", delivery(TOMORROW, "55100", "Lucca"));

    assertEquals(303, response.statusCode(), "status; body: " + body(response));
    Map<String, Object> order = data.onlyDemoOrder();
    assertEquals(
        data.zoneId(DEMO_ID, LUCCA), ((Number) column(order, "zone_id")).longValue(), "zone");
    assertEquals(500L, cents(order, "delivery_fee_cents"), "fee of the older zone");
  }
}
