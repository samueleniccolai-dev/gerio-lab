package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro005DemoData.MARCO_ID;
import static it.gerio.platform.acceptance.Giro008Browser.CART_COOKIE;
import static it.gerio.platform.acceptance.Giro008Browser.CSRF;
import static it.gerio.platform.acceptance.Giro008Browser.NBSP;
import static it.gerio.platform.acceptance.Giro008Browser.body;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro008CartData.MARCO;
import static it.gerio.platform.acceptance.Giro008CartData.PLATFORM;
import static it.gerio.platform.acceptance.Giro010Checkout.EMAIL;
import static it.gerio.platform.acceptance.Giro010Checkout.NAME;
import static it.gerio.platform.acceptance.Giro010Checkout.PAY;
import static it.gerio.platform.acceptance.Giro010Checkout.assertLine;
import static it.gerio.platform.acceptance.Giro010Checkout.assertToStripe;
import static it.gerio.platform.acceptance.Giro010Checkout.fillDemoCart;
import static it.gerio.platform.acceptance.Giro010Checkout.form;
import static it.gerio.platform.acceptance.Giro010Checkout.lineItems;
import static it.gerio.platform.acceptance.Giro010Checkout.with;
import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro010CheckoutData.MARCO_ACCOUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
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
 * Giro 010 (goal T5, part 1): from the cart of the demo shop to a Stripe Checkout session on the
 * shop's connected account. Black box over HTTP on demo.localhost; Stripe is the fake of {@link
 * Giro010FakeStripe}; the clock is fixed at 12/10/2026 08:00 UTC (10:00 in Rome). Data: demo seed,
 * Marco's shop, Giulia Bianchi (docs/lab/giri/010-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro010FakeStripe.class)
class Giro010CheckoutAcceptanceTest {

  static final Instant T0 = Instant.parse("2026-10-12T08:00:00Z");
  static final String TOMORROW = "2026-10-13";

  @Inject DataSource dataSource;

  private Giro010CheckoutData data;
  private Giro010FakeStripe stripe;
  private final TestClock clock = new TestClock(T0);

  @BeforeEach
  void setUp() throws IOException, SQLException {
    QuarkusMock.installMockForType(clock, Clock.class);
    stripe = Giro010FakeStripe.get();
    stripe.reset();
    data = new Giro010CheckoutData(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  @Test
  void theCartLinksToTheCheckoutAndTheSummaryShowsRowsTotalAndForm() {
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);
    assertTrue(
        browser.cartPage().contains("href=\"/checkout\""),
        "the cart page has no link to /checkout");

    Response response = browser.get("/checkout");
    assertEquals(200, response.statusCode(), "status of GET /checkout");
    String html = body(response);
    assertTrue(html.contains("Mazzo di rose rosse"), "summary without the roses");
    assertTrue(html.contains("12 rose"), "summary without the roses variant");
    assertTrue(html.contains("Orchidea Phalaenopsis bianca"), "summary without the orchid");
    assertTrue(html.contains("Vaso 12 cm"), "summary without the orchid variant");
    assertTrue(html.contains("104,80" + NBSP + "€"), "summary without the total 104,80 €");
    assertTrue(
        Pattern.compile(
                "<form[^>]*action=[\"']/checkout[\"'][^>]*method=[\"']post[\"']|"
                    + "<form[^>]*method=[\"']post[\"'][^>]*action=[\"']/checkout[\"']",
                Pattern.CASE_INSENSITIVE)
            .matcher(html)
            .find(),
        "no <form method=post action=/checkout>");
    for (String field : List.of("customerName", "customerEmail", "customerPhone", "pickupDate")) {
      assertTrue(
          Pattern.compile("<input[^>]*name=[\"']" + field + "[\"']").matcher(html).find(),
          "no input " + field);
    }
    assertTrue(
        Pattern.compile(
                "<input[^>]*type=[\"']date[\"'][^>]*name=[\"']pickupDate[\"']|"
                    + "<input[^>]*name=[\"']pickupDate[\"'][^>]*type=[\"']date[\"']")
            .matcher(html)
            .find(),
        "pickupDate is not an <input type=date>");
    assertTrue(html.contains("href=\"/condizioni-di-vendita\""), "no link to the sales terms");
    assertTrue(html.contains(PAY), "no \"" + PAY + "\" button");
    assertTrue(html.contains("name=\"" + CSRF + "\""), "the checkout form has no CSRF field");
  }

  @Test
  void anEmptyCartOrNoCookieRedirectsToTheCart() {
    Giro008Browser noCookie = new Giro008Browser(DEMO);
    assertToCart(noCookie.get("/checkout"), "GET /checkout without a cart cookie");

    Giro008Browser emptied = new Giro008Browser(DEMO);
    emptied.page("/prodotti/orchidea-bianca", 200);
    emptied.addOk(data.cart.orchid, 1);
    long item = Giro008Browser.itemId(emptied.cartPage(), "orchidea-bianca");
    Giro008Browser.assertSeeOther(emptied.remove(item), "remove the only row");
    assertToCart(emptied.get("/checkout"), "GET /checkout with an empty cart");
  }

  @Test
  void aValidOrderCreatesAPendingOrderAndRedirectsToTheStripeSessionOfTheShop()
      throws SQLException {
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);
    browser.page("/checkout", 200);

    Response response = browser.post("/checkout", form(TOMORROW));

    List<Giro010FakeStripe.Call> calls = stripe.sessionCalls();
    assertEquals(1, calls.size(), "session requests received by Stripe");
    assertEquals(1, stripe.createdSessions().size(), "sessions created");
    String session = stripe.createdSessions().get(0);
    assertToStripe(response, session, "POST /checkout");

    List<Map<String, Object>> orders = data.orders(DEMO_ID);
    assertEquals(1, orders.size(), "orders of the demo");
    Map<String, Object> order = orders.get(0);
    long orderId = ((Number) order.get("id")).longValue();
    assertEquals("2026-0001", order.get("number"), "order number");
    assertEquals("PENDING_PAYMENT", order.get("status"), "order status");
    assertEquals("PICKUP", order.get("fulfillment_type"), "fulfillment type");
    assertEquals(NAME, order.get("customer_name"), "customer name");
    assertEquals(EMAIL, order.get("customer_email"), "customer email");
    assertEquals(TOMORROW, String.valueOf(order.get("delivery_date")), "pickup date");
    assertEquals(10480L, ((Number) order.get("subtotal_cents")).longValue(), "subtotal");
    assertEquals(0L, ((Number) order.get("delivery_fee_cents")).longValue(), "delivery fee");
    assertEquals(10480L, ((Number) order.get("total_cents")).longValue(), "total");
    assertNotNull(order.get("terms_version"), "terms_version");
    assertNotNull(order.get("public_token"), "public_token");
    assertTrue(
        String.valueOf(order.get("public_token")).length() >= 22,
        "public_token shorter than 128 bits: " + order.get("public_token"));
    assertEquals(session, order.get("stripe_checkout_session_id"), "stored session id");
    assertEquals(
        T0.plus(Duration.ofMinutes(35)),
        ((Timestamp) order.get("hold_expires_at")).toInstant(),
        "hold_expires_at = now + 35 min (§7.4)");

    List<Map<String, Object>> events = data.events(orderId);
    assertEquals(1, events.size(), "order events");
    assertNull(events.get(0).get("from_status"), "first event from_status");
    assertEquals("PENDING_PAYMENT", events.get(0).get("to_status"), "first event to_status");

    Giro010FakeStripe.Call call = calls.get(0);
    Map<String, String> p = call.form();
    assertEquals("Bearer " + Giro010FakeStripe.SECRET_KEY, call.header("Authorization"), "key");
    assertEquals(DEMO_ACCOUNT, call.header("Stripe-Account"), "Stripe-Account header");
    assertEquals("order-" + orderId + "-attempt-1", call.idempotencyKey(), "idempotency key");
    assertEquals("payment", p.get("mode"), "mode");
    assertEquals("eur", p.get("currency"), "currency");
    assertEquals(Long.toString(orderId), p.get("client_reference_id"), "client_reference_id");
    assertEquals(DEMO_ID, p.get("metadata[tenant_id]"), "metadata[tenant_id]");
    assertEquals("2026-0001", p.get("metadata[order_number]"), "metadata[order_number]");
    assertEquals(EMAIL, p.get("customer_email"), "customer_email");
    long expiresAt = Long.parseLong(p.get("expires_at"));
    long now = T0.getEpochSecond();
    assertTrue(
        expiresAt > now + 30 * 60 && expiresAt <= now + 35 * 60,
        "expires_at must be in (now+30min, now+35min]: " + (expiresAt - now) + " s");
    assertTrue(
        p.getOrDefault("success_url", "")
            .matches(
                "https://demo\\.localhost(:\\d+)?/ordine/esito\\?session_id=\\{CHECKOUT_SESSION_ID}"),
        "success_url: " + p.get("success_url"));
    assertTrue(
        p.getOrDefault("cancel_url", "").matches("https://demo\\.localhost(:\\d+)?/checkout"),
        "cancel_url: " + p.get("cancel_url"));
    assertTrue(
        p.keySet().stream().noneMatch(k -> k.startsWith("payment_method_types")),
        "payment methods must come from the shop's Stripe settings (§8.3)");

    List<Map<String, String>> lines = lineItems(p);
    assertEquals(2, lines.size(), "Stripe line items: " + lines);
    assertLine(lines, "Mazzo di rose rosse", "12 rose", 4500, 1);
    assertLine(lines, "Orchidea Phalaenopsis bianca", "Vaso 12 cm", 2990, 2);

    String cart = browser.cartPage();
    assertEquals(2, Giro008Browser.rowCount(cart), "the cart is kept until the payment");
  }

  @Test
  void pricesComeFromTheDatabaseAtCheckoutTimeNotFromTheBrowser() throws SQLException {
    data.setPrice(data.rose12, 3900);
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);
    data.setPrice(data.rose12, 4500);
    browser.page("/checkout", 200);

    Map<String, String> tampered = new LinkedHashMap<>(form(TOMORROW));
    tampered.put("price_cents", "1");
    tampered.put("unit_amount", "1");
    tampered.put("total_cents", "1");
    Response response = browser.post("/checkout", tampered);
    assertEquals(1, stripe.createdSessions().size(), "sessions created");
    assertToStripe(response, stripe.createdSessions().get(0), "POST /checkout with prices");

    List<Map<String, String>> lines = lineItems(stripe.sessionCalls().get(0).form());
    assertLine(lines, "Mazzo di rose rosse", "12 rose", 4500, 1);
    assertLine(lines, "Orchidea Phalaenopsis bianca", "Vaso 12 cm", 2990, 2);

    Map<String, Object> order = data.orders(DEMO_ID).get(0);
    assertEquals(10480L, ((Number) order.get("total_cents")).longValue(), "total");
    List<Map<String, Object>> items = data.items(((Number) order.get("id")).longValue());
    assertEquals(2, items.size(), "order items");
    Map<String, Object> rose = item(items, "Mazzo di rose rosse");
    assertTrue(String.valueOf(rose.get("name_snapshot")).contains("12 rose"), "rose snapshot");
    assertEquals(4500L, ((Number) rose.get("unit_price_cents")).longValue(), "rose unit price");
    assertEquals(1L, ((Number) rose.get("quantity")).longValue(), "rose quantity");
    assertEquals(4500L, ((Number) rose.get("line_total_cents")).longValue(), "rose line total");
    Map<String, Object> orchid = item(items, "Orchidea Phalaenopsis bianca");
    assertEquals(2990L, ((Number) orchid.get("unit_price_cents")).longValue(), "orchid unit");
    assertEquals(2L, ((Number) orchid.get("quantity")).longValue(), "orchid quantity");
    assertEquals(5980L, ((Number) orchid.get("line_total_cents")).longValue(), "orchid line");
  }

  @Test
  void invalidDataIsRejectedWithoutOrderAndWithoutCallingStripe() throws SQLException {
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);
    browser.page("/checkout", 200);
    Map<String, String> valid = form(TOMORROW);

    Map<String, Map<String, String>> cases = new LinkedHashMap<>();
    cases.put("empty email", with(valid, "customerEmail", ""));
    cases.put("email without @", with(valid, "customerEmail", "giulia.bianchi"));
    cases.put("empty name", with(valid, "customerName", " "));
    cases.put("past date", with(valid, "pickupDate", "2026-10-11"));
    cases.put("bad date", with(valid, "pickupDate", "13/10/2026"));
    cases.put("missing date", with(valid, "pickupDate", ""));
    for (Map.Entry<String, Map<String, String>> c : cases.entrySet()) {
      Response response = browser.post("/checkout", c.getValue());
      assertEquals(400, response.statusCode(), c.getKey() + ": status");
      assertTrue(
          body(response).contains("action=\"/checkout\""), c.getKey() + ": the form is not shown");
      assertEquals(0, data.countOrders(), c.getKey() + ": an order was created");
      assertEquals(0, stripe.allCalls().size(), c.getKey() + ": Stripe was called");
    }

    // Positive control: the same browser and token with valid data go to Stripe.
    Response ok = browser.post("/checkout", valid);
    assertEquals(1, stripe.createdSessions().size(), "positive control: sessions");
    assertToStripe(ok, stripe.createdSessions().get(0), "positive control");
  }

  @Test
  void aShopWithoutChargesEnabledShowsContactsAndNeverCallsStripe() throws SQLException {
    data.connect(DEMO_ID, DEMO_ACCOUNT, false);
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);

    String html = browser.page("/checkout", 200);
    assertFalse(html.contains(PAY), "\"" + PAY + "\" shown with charges disabled");
    assertTrue(html.contains("+39 0583 000000"), "the shop's phone is not shown");

    Response response = browser.post("/checkout", form(TOMORROW));
    assertEquals(409, response.statusCode(), "POST /checkout with charges disabled");
    assertEquals(0, data.countOrders(), "orders created");
    assertEquals(0, stripe.allCalls().size(), "Stripe was called");
  }

  @Test
  void aPostWithoutTheCsrfTokenIsRejectedWithoutOrder() throws SQLException {
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);
    browser.page("/checkout", 200);

    Response response =
        browser.postRaw("/checkout", form(TOMORROW), new LinkedHashMap<>(browser.cookies));
    assertEquals(400, response.statusCode(), "POST /checkout without the CSRF field");
    assertEquals(0, data.countOrders(), "orders created");
    assertEquals(0, stripe.allCalls().size(), "Stripe was called");
  }

  @Test
  void thePlatformDomainHasNoCheckout() throws SQLException {
    Giro008Browser demo = new Giro008Browser(DEMO);
    fillDemoCart(demo, data);
    String token = demo.token();

    Response get =
        given()
            .redirects()
            .follow(false)
            .header("Host", PLATFORM)
            .cookies(demo.cookies)
            .when()
            .get("/checkout");
    assertEquals(404, get.statusCode(), "GET /checkout on the platform domain");

    Map<String, String> form = new LinkedHashMap<>(form(TOMORROW));
    form.put(CSRF, token);
    Response post =
        given()
            .redirects()
            .follow(false)
            .header("Host", PLATFORM)
            .cookies(demo.cookies)
            .contentType("application/x-www-form-urlencoded")
            .formParams(form)
            .when()
            .post("/checkout");
    assertEquals(404, post.statusCode(), "POST /checkout on the platform domain (valid token)");
    assertEquals(0, data.countOrders(), "orders created");
    assertEquals(0, stripe.allCalls().size(), "Stripe was called");
  }

  @Test
  void theDemoCartCookieOnAnotherShopCreatesNoOrderAnywhere() throws SQLException {
    Giro008Browser demo = new Giro008Browser(DEMO);
    fillDemoCart(demo, data);
    String demoCart = demo.cookies.get(CART_COOKIE);
    assertNotNull(demoCart, "no cart cookie on the demo");

    Giro008Browser marco = new Giro008Browser(MARCO);
    marco.page("/prodotti/bouquet-di-stagione", 200);
    marco.cookies.put(CART_COOKIE, demoCart);

    assertToCart(marco.get("/checkout"), "GET /checkout on Marco with the demo cookie");
    assertToCart(
        marco.post("/checkout", form(TOMORROW)), "POST /checkout on Marco with the demo cookie");
    assertEquals(0, data.orders(DEMO_ID).size(), "orders of the demo");
    assertEquals(0, data.orders(MARCO_ID).size(), "orders of Marco");
    assertEquals(0, stripe.allCalls().size(), "Stripe was called");
  }

  @Test
  void numbersFollowEachOtherInAShopAndRestartInAnotherShop() throws SQLException {
    Giro008Browser first = new Giro008Browser(DEMO);
    fillDemoCart(first, data);
    assertEquals(303, first.post("/checkout", form(TOMORROW)).statusCode(), "first demo order");

    Giro008Browser second = new Giro008Browser(DEMO);
    second.page("/prodotti/orchidea-bianca", 200);
    second.addOk(data.cart.orchid, 1);
    assertEquals(303, second.post("/checkout", form(TOMORROW)).statusCode(), "second demo order");

    Giro008Browser marco = new Giro008Browser(MARCO);
    marco.page("/prodotti/bouquet-di-stagione", 200);
    marco.addOk(data.cart.marco, 1);
    assertEquals(303, marco.post("/checkout", form(TOMORROW)).statusCode(), "Marco's order");

    assertEquals(
        List.of("2026-0001", "2026-0002"), numbers(data.orders(DEMO_ID)), "demo order numbers");
    List<Map<String, Object>> marcoOrders = data.orders(MARCO_ID);
    assertEquals(List.of("2026-0001"), numbers(marcoOrders), "Marco's order numbers");
    assertEquals(
        3000L, ((Number) marcoOrders.get(0).get("total_cents")).longValue(), "Marco total");

    Giro010FakeStripe.Call marcoCall = stripe.sessionCalls().get(2);
    assertEquals(MARCO_ACCOUNT, marcoCall.header("Stripe-Account"), "Marco's Stripe-Account");
    assertEquals(MARCO_ID, marcoCall.form().get("metadata[tenant_id]"), "Marco's tenant_id");
    assertEquals("2026-0001", marcoCall.form().get("metadata[order_number]"), "Marco's number");
  }

  // --- helpers --------------------------------------------------------------------------------

  static void assertToCart(Response response, String what) {
    assertEquals(303, response.statusCode(), what + ": status; body: " + body(response));
    String location = response.getHeader("Location");
    assertNotNull(location, what + ": no Location");
    assertTrue(location.matches("(https?://[^/]+)?/carrello"), what + ": Location is " + location);
  }

  static List<Object> numbers(List<Map<String, Object>> orders) {
    return orders.stream().map(o -> o.get("number")).toList();
  }

  private static Map<String, Object> item(List<Map<String, Object>> items, String name) {
    for (Map<String, Object> item : items) {
      if (String.valueOf(item.get("name_snapshot")).contains(name)) {
        return item;
      }
    }
    throw new AssertionError("no order item named like \"" + name + "\": " + items);
  }
}
