package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro010Checkout.fillDemoCart;
import static it.gerio.platform.acceptance.Giro010Checkout.form;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Giro 011 test helpers (docs/lab/giri/011-piano.md): Stripe-like events written as JSON, signed
 * like Stripe does ({@code Stripe-Signature: t=<epoch>,v1=<hex HMAC-SHA256 of "t.payload">}), sent
 * to the webhook over HTTP; and orders placed through the real {@code POST /checkout}.
 */
final class Giro011Webhooks {

  static final String SECRET = "whsec_giro011_finto";
  static final String PATH = "/webhooks/stripe/connect";
  static final String COMPLETED = "checkout.session.completed";
  static final String EXPIRED = "checkout.session.expired";
  static final String TOMORROW = Giro010CheckoutAcceptanceTest.TOMORROW;

  /** The API version of the stripe-java library on the classpath (2026-09-30.endive in 34.0.0). */
  static final String API_VERSION = com.stripe.Stripe.API_VERSION;

  /** An order placed through the checkout, with the browser (and cart cookie) that placed it. */
  record Placed(Giro008Browser browser, long orderId, String number, String sessionId) {}

  private Giro011Webhooks() {}

  // --- orders ---------------------------------------------------------------------------------

  /** Demo cart 1 x 12 roses + 2 x orchid (10480), then "Ordina e paga". */
  static Placed placeDemoOrder(Giro010CheckoutData data, Giro010FakeStripe stripe)
      throws Exception {
    Giro008Browser browser = new Giro008Browser(Giro008CartData.DEMO);
    fillDemoCart(browser, data);
    return checkout(browser, data, stripe, Giro005DemoData.DEMO_ID);
  }

  /** "Ordina e paga" with the browser's current cart; the new or reused order of that cart. */
  static Placed checkout(
      Giro008Browser browser, Giro010CheckoutData data, Giro010FakeStripe stripe, String tenantId)
      throws Exception {
    browser.page("/checkout", 200);
    Response response = browser.post("/checkout", form(TOMORROW));
    assertEquals(
        303, response.statusCode(), "POST /checkout; body: " + Giro008Browser.body(response));
    List<String> sessions = stripe.createdSessions();
    assertFalse(sessions.isEmpty(), "no Stripe session created");
    String session = sessions.get(sessions.size() - 1);
    assertEquals(Giro010FakeStripe.urlOf(session), response.getHeader("Location"), "Location");
    List<Map<String, Object>> orders = data.orders(tenantId);
    Map<String, Object> order = orders.get(orders.size() - 1);
    return new Placed(
        browser, ((Number) order.get("id")).longValue(), (String) order.get("number"), session);
  }

  // --- events ---------------------------------------------------------------------------------

  /** A Checkout Session object as Stripe sends it in {@code data.object}. */
  static String session(
      String sessionId,
      long orderId,
      String tenantId,
      String orderNumber,
      String status,
      String paymentStatus,
      String paymentIntent) {
    return "{\"id\":\""
        + sessionId
        + "\",\"object\":\"checkout.session\",\"livemode\":false,\"mode\":\"payment\""
        + ",\"status\":\""
        + status
        + "\",\"payment_status\":\""
        + paymentStatus
        + "\",\"payment_intent\":"
        + (paymentIntent == null ? "null" : "\"" + paymentIntent + "\"")
        + ",\"client_reference_id\":\""
        + orderId
        + "\",\"currency\":\"eur\",\"amount_total\":10480"
        + ",\"metadata\":{\"tenant_id\":\""
        + tenantId
        + "\",\"order_number\":\""
        + orderNumber
        + "\"}}";
  }

  /** Paid session of a placed demo order. */
  static String paidSession(Placed order, String sessionId, String paymentIntent) {
    return session(
        sessionId,
        order.orderId(),
        Giro005DemoData.DEMO_ID,
        order.number(),
        "complete",
        "paid",
        paymentIntent);
  }

  /** Expired session of a placed demo order. */
  static String expiredSession(Placed order, String sessionId) {
    return session(
        sessionId,
        order.orderId(),
        Giro005DemoData.DEMO_ID,
        order.number(),
        "expired",
        "unpaid",
        null);
  }

  /** An event of a connected account ({@code account} omitted when null). */
  static String event(String id, String type, String account, String apiVersion, String object) {
    return "{\"id\":\""
        + id
        + "\",\"object\":\"event\",\"api_version\":\""
        + apiVersion
        + "\",\"created\":"
        + Instant.now().getEpochSecond()
        + ",\"livemode\":false,\"pending_webhooks\":1"
        + ",\"request\":{\"id\":null,\"idempotency_key\":null}"
        + ",\"type\":\""
        + type
        + "\""
        + (account == null ? "" : ",\"account\":\"" + account + "\"")
        + ",\"data\":{\"object\":"
        + object
        + "}}";
  }

  static String event(String id, String type, String account, String object) {
    return event(id, type, account, API_VERSION, object);
  }

  // --- signature ------------------------------------------------------------------------------

  /** {@code t=<epoch>,v1=<hex>} for the payload, signed with the secret at the given instant. */
  static String signature(String payload, String secret, Instant at) {
    long t = at.getEpochSecond();
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8));
      return "t=" + t + ",v1=" + HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Signed now (real time, as Stripe does) with the test secret. */
  static String signature(String payload) {
    return signature(payload, SECRET, Instant.now());
  }

  // --- HTTP -----------------------------------------------------------------------------------

  /** POST of the exact bytes, with the given Stripe-Signature (none if null). */
  static Response post(String host, String payload, String signature) {
    RequestSpecification request =
        given()
            .redirects()
            .follow(false)
            .header("Host", host)
            .contentType("application/json")
            .body(payload.getBytes(StandardCharsets.UTF_8));
    if (signature != null) {
      request = request.header("Stripe-Signature", signature);
    }
    return request.when().post(PATH);
  }

  /** Signed POST on the platform domain. */
  static Response send(String payload) {
    return post(Giro008CartData.PLATFORM, payload, signature(payload));
  }

  /** Signed POST on the platform domain that must answer 200. */
  static void sendOk(String payload, String what) {
    Response response = send(payload);
    assertEquals(
        200, response.statusCode(), what + ": status; body: " + Giro008Browser.body(response));
  }
}
