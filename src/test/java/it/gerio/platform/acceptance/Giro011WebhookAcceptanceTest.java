package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro005DemoData.MARCO_ID;
import static it.gerio.platform.acceptance.Giro008Browser.body;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro008CartData.MARCO;
import static it.gerio.platform.acceptance.Giro008CartData.PLATFORM;
import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro010CheckoutData.MARCO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.EXPIRED;
import static it.gerio.platform.acceptance.Giro011Webhooks.SECRET;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.expiredSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.paidSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.placeDemoOrder;
import static it.gerio.platform.acceptance.Giro011Webhooks.post;
import static it.gerio.platform.acceptance.Giro011Webhooks.send;
import static it.gerio.platform.acceptance.Giro011Webhooks.sendOk;
import static it.gerio.platform.acceptance.Giro011Webhooks.signature;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import it.gerio.platform.acceptance.Giro011Webhooks.Placed;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 011 (goal T5, part 2): the signed, idempotent Stripe webhook {@code POST
 * /webhooks/stripe/connect} on the platform domain marks the order PAID or EXPIRED once, empties
 * the cart when paid, rejects bad signatures, and never touches another shop. Every order comes
 * from the real {@code POST /checkout} with the fake Stripe; events are JSON signed in the test
 * with the invented secret {@code whsec_giro011_finto} (docs/lab/giri/011-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro011WebhookAcceptanceTest {

  private static final Instant T0 = Giro010CheckoutAcceptanceTest.T0;
  private static final Instant PAID_AT = T0.plus(Duration.ofMinutes(10));
  private static final String PI = "pi_giro011_demo_1";
  private static final String EMPTY_CART = "Il tuo carrello è vuoto.";

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

  private Placed order() throws Exception {
    return placeDemoOrder(data.checkout, stripe);
  }

  private String paid(String eventId, Placed order) {
    return event(eventId, COMPLETED, DEMO_ACCOUNT, paidSession(order, order.sessionId(), PI));
  }

  private String expired(String eventId, Placed order) {
    return event(eventId, EXPIRED, DEMO_ACCOUNT, expiredSession(order, order.sessionId()));
  }

  // --- PAID -----------------------------------------------------------------------------------

  @Test
  void aSignedPaidEventMarksTheOrderPaidOnceAndEmptiesTheCart() throws Exception {
    Placed order = order();
    clock.set(PAID_AT);

    sendOk(paid("evt_giro011_paid_1", order), "completed/paid");

    assertEquals("PAID", data.status(order.orderId()), "status after the paid event");
    Object paidAt = data.column(order.orderId(), "paid_at");
    assertNotNull(paidAt, "paid_at not set");
    assertEquals(PAID_AT, ((Timestamp) paidAt).toInstant(), "paid_at = instant of the Clock");
    assertEquals(
        PI, data.column(order.orderId(), "stripe_payment_intent_id"), "stripe_payment_intent_id");
    List<Map<String, Object>> toPaid =
        data.events(order.orderId()).stream()
            .filter(e -> "PAID".equals(e.get("to_status")))
            .toList();
    assertEquals(1, toPaid.size(), "order events to PAID");
    assertEquals("PENDING_PAYMENT", toPaid.get(0).get("from_status"), "from_status of PAID");

    Map<String, Object> row = data.stripeEvent("evt_giro011_paid_1");
    assertNotNull(row, "no stripe_event row");
    assertEquals(COMPLETED, row.get("type"), "stripe_event.type");
    assertEquals(DEMO_ACCOUNT, row.get("account"), "stripe_event.account");
    assertNotNull(row.get("processed_at"), "stripe_event.processed_at not set");

    assertTrue(order.browser().cartPage().contains(EMPTY_CART), "the cart is not empty after PAID");
  }

  @Test
  void theSameEventDeliveredAgainChangesNothing() throws Exception {
    Placed order = order();
    String payload = paid("evt_giro011_paid_1", order);
    sendOk(payload, "first delivery");
    sendOk(payload, "second delivery");

    assertEquals("PAID", data.status(order.orderId()), "status");
    assertEquals(1, data.eventsTo(order.orderId(), "PAID"), "order events to PAID");
    assertEquals(1, data.countStripeEvents(), "stripe_event rows");
  }

  @Test
  void theSameEventTwiceInParallelMarksPaidOnceTenTimesInARow() throws Exception {
    int pairs = 10;
    List<Placed> orders = new ArrayList<>();
    for (int i = 0; i < pairs; i++) {
      orders.add(order());
    }
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < pairs; i++) {
        Placed order = orders.get(i);
        String payload = paid("evt_giro011_parallel_" + i, order);
        String signature = signature(payload);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Response>> results = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
          results.add(
              pool.submit(
                  () -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return post(PLATFORM, payload, signature);
                  }));
        }
        for (Future<Response> result : results) {
          Response response = result.get(60, TimeUnit.SECONDS);
          assertEquals(
              200, response.statusCode(), "pair " + i + ": status; body: " + body(response));
        }
        assertEquals("PAID", data.status(order.orderId()), "pair " + i + ": status");
        assertEquals(
            1, data.eventsTo(order.orderId(), "PAID"), "pair " + i + ": order events to PAID");
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(pairs, data.countStripeEvents(), "stripe_event rows");
  }

  @Test
  void anEventLeftUnprocessedByAnEarlierDeliveryIsProcessed() throws Exception {
    Placed order = order();
    data.insertUnprocessed("evt_giro011_paid_1", COMPLETED, DEMO_ACCOUNT);

    sendOk(paid("evt_giro011_paid_1", order), "delivery after a failed one");

    assertEquals("PAID", data.status(order.orderId()), "status");
    assertNotNull(data.stripeEvent("evt_giro011_paid_1").get("processed_at"), "processed_at");
    assertEquals(1, data.countStripeEvents(), "stripe_event rows");
  }

  @Test
  void aSecondPaidEventWithAnotherPaymentIntentKeepsTheFirstPayment() throws Exception {
    Placed order = order();
    sendOk(paid("evt_giro011_paid_1", order), "first paid");
    sendOk(
        event(
            "evt_giro011_paid_2",
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(order, order.sessionId(), "pi_giro011_other")),
        "second paid with another payment intent");

    assertEquals("PAID", data.status(order.orderId()), "status");
    assertEquals(
        PI, data.column(order.orderId(), "stripe_payment_intent_id"), "first payment intent kept");
    assertEquals(1, data.eventsTo(order.orderId(), "PAID"), "order events to PAID");
    assertNotNull(data.stripeEvent("evt_giro011_paid_2").get("processed_at"), "processed_at");
  }

  @Test
  void onlyCompleteAndPaidMarksTheOrderPaid() throws Exception {
    Placed order = order();
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("unpaid", sessionWith(order, "complete", "unpaid"));
    cases.put("no_payment_required", sessionWith(order, "complete", "no_payment_required"));
    cases.put("open and paid", sessionWith(order, "open", "paid"));
    int i = 0;
    for (Map.Entry<String, String> c : cases.entrySet()) {
      String id = "evt_giro011_unpaid_" + ++i;
      sendOk(event(id, COMPLETED, DEMO_ACCOUNT, c.getValue()), c.getKey());
      assertEquals("PENDING_PAYMENT", data.status(order.orderId()), c.getKey() + ": status");
      assertNotNull(data.stripeEvent(id).get("processed_at"), c.getKey() + ": processed_at");
    }
    assertEquals(1, data.events(order.orderId()).size(), "order events");
    assertEquals(2, Giro008Browser.rowCount(order.browser().cartPage()), "cart rows");
  }

  private static String sessionWith(Placed order, String status, String paymentStatus) {
    return Giro011Webhooks.session(
        order.sessionId(), order.orderId(), DEMO_ID, order.number(), status, paymentStatus, PI);
  }

  // --- EXPIRED and order of events ------------------------------------------------------------

  @Test
  void anExpiredEventMarksTheOrderExpiredAndKeepsTheCart() throws Exception {
    Placed order = order();
    sendOk(expired("evt_giro011_expired_1", order), "expired");

    assertEquals("EXPIRED", data.status(order.orderId()), "status");
    List<Map<String, Object>> toExpired =
        data.events(order.orderId()).stream()
            .filter(e -> "EXPIRED".equals(e.get("to_status")))
            .toList();
    assertEquals(1, toExpired.size(), "order events to EXPIRED");
    assertEquals("PENDING_PAYMENT", toExpired.get(0).get("from_status"), "from_status");
    assertEquals(2, Giro008Browser.rowCount(order.browser().cartPage()), "cart rows kept");
  }

  @Test
  void eventsOutOfOrderDoNotChangeAFinalState() throws Exception {
    Placed paidFirst = order();
    sendOk(paid("evt_giro011_paid_1", paidFirst), "paid");
    int eventsPaid = data.events(paidFirst.orderId()).size();
    sendOk(expired("evt_giro011_expired_1", paidFirst), "expired after paid");
    assertEquals("PAID", data.status(paidFirst.orderId()), "expired after PAID");
    assertEquals(eventsPaid, data.events(paidFirst.orderId()).size(), "events after expired");

    Placed expiredFirst = order();
    sendOk(expired("evt_giro011_expired_2", expiredFirst), "expired");
    int eventsExpired = data.events(expiredFirst.orderId()).size();
    sendOk(paid("evt_giro011_paid_2", expiredFirst), "paid after expired");
    assertEquals("EXPIRED", data.status(expiredFirst.orderId()), "completed after EXPIRED");
    assertEquals(eventsExpired, data.events(expiredFirst.orderId()).size(), "events after paid");
  }

  // --- older sessions of the same order (critique point 2) ------------------------------------

  /** First session at T0, then after its expiry (31 min) the same cart gets a second session. */
  private Placed[] twoSessions() throws Exception {
    Placed first = order();
    clock.set(T0.plus(Duration.ofMinutes(32)));
    Placed second = Giro011Webhooks.checkout(first.browser(), data.checkout, stripe, DEMO_ID);
    assertEquals(first.orderId(), second.orderId(), "the second attempt is the same order");
    assertNotEquals(first.sessionId(), second.sessionId(), "the second attempt has a new session");
    return new Placed[] {first, second};
  }

  @Test
  void aPaymentOnAnOlderSessionOfTheOrderMarksItPaid() throws Exception {
    Placed[] sessions = twoSessions();
    Placed old = sessions[0];
    sendOk(
        event(
            "evt_giro011_paid_old",
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(old, old.sessionId(), "pi_giro011_old")),
        "completed of the older session");

    assertEquals("PAID", data.status(old.orderId()), "status");
    assertEquals(
        "pi_giro011_old",
        data.column(old.orderId(), "stripe_payment_intent_id"),
        "payment intent of the older session");
    assertEquals(
        old.sessionId(),
        data.column(old.orderId(), "stripe_checkout_session_id"),
        "the paying session is the one saved on the order");
  }

  @Test
  void anExpiredOlderSessionDoesNotExpireTheOrder() throws Exception {
    Placed[] sessions = twoSessions();
    Placed old = sessions[0];
    Placed current = sessions[1];
    int before = data.events(old.orderId()).size();

    sendOk(
        event(
            "evt_giro011_expired_old", EXPIRED, DEMO_ACCOUNT, expiredSession(old, old.sessionId())),
        "expired of the older session");
    assertEquals("PENDING_PAYMENT", data.status(old.orderId()), "status after the old expiry");
    assertEquals(before, data.events(old.orderId()).size(), "order events after the old expiry");

    sendOk(
        event(
            "evt_giro011_expired_current",
            EXPIRED,
            DEMO_ACCOUNT,
            expiredSession(current, current.sessionId())),
        "expired of the current session");
    assertEquals("EXPIRED", data.status(current.orderId()), "status after the current expiry");
  }

  // --- API version and malformed objects (critique point 3) -----------------------------------

  @Test
  void anEventWithAnotherApiVersionIsStillProcessed() throws Exception {
    Placed order = order();
    sendOk(
        event(
            "evt_giro011_paid_old_api",
            COMPLETED,
            DEMO_ACCOUNT,
            "2024-06-20",
            paidSession(order, order.sessionId(), PI)),
        "completed with api_version 2024-06-20");
    assertEquals("PAID", data.status(order.orderId()), "status");
    assertEquals(PI, data.column(order.orderId(), "stripe_payment_intent_id"), "payment intent");
  }

  @Test
  void anObjectThatCannotBeReadGives500AndStaysUnprocessed() throws Exception {
    Placed order = order();
    String object =
        paidSession(order, order.sessionId(), PI)
            .replace("\"payment_status\":\"paid\"", "\"payment_status\":{\"unexpected\":true}");
    Response response = send(event("evt_giro011_broken_1", COMPLETED, DEMO_ACCOUNT, object));

    assertEquals(500, response.statusCode(), "status for an unreadable session object");
    Map<String, Object> row = data.stripeEvent("evt_giro011_broken_1");
    assertTrue(row == null || row.get("processed_at") == null, "the event was marked processed");
    assertEquals("PENDING_PAYMENT", data.status(order.orderId()), "status");
  }

  // --- signature ------------------------------------------------------------------------------

  @Test
  void badSignaturesGet400AndWriteNothing() throws Exception {
    Placed order = order();
    String payload = paid("evt_giro011_paid_1", order);
    Instant now = Instant.now();

    Map<String, Response> cases = new LinkedHashMap<>();
    cases.put("no header", post(PLATFORM, payload, null));
    cases.put("wrong secret", post(PLATFORM, payload, signature(payload, "whsec_altro", now)));
    cases.put(
        "signed 10 minutes ago",
        post(PLATFORM, payload, signature(payload, SECRET, now.minus(Duration.ofMinutes(10)))));
    cases.put(
        "body changed after signing",
        post(PLATFORM, payload.replace(PI, "pi_giro011_forged"), signature(payload)));
    // The tolerance is on real time, as Stripe signs: an app Clock set to the signing instant must
    // not make a 10-minute-old signature valid.
    clock.set(now.minus(Duration.ofMinutes(10)));
    cases.put(
        "signed 10 minutes ago, app Clock at that instant",
        post(PLATFORM, payload, signature(payload, SECRET, now.minus(Duration.ofMinutes(10)))));

    for (Map.Entry<String, Response> c : cases.entrySet()) {
      assertEquals(400, c.getValue().statusCode(), c.getKey() + ": status");
    }
    assertEquals(0, data.countStripeEvents(), "stripe_event rows after bad signatures");
    assertEquals("PENDING_PAYMENT", data.status(order.orderId()), "status");
  }

  @Test
  void aFreshSignatureIsValidWhateverTheAppClockSays() throws Exception {
    Placed order = order();
    clock.set(Instant.now().plus(Duration.ofDays(1)));
    sendOk(paid("evt_giro011_paid_1", order), "fresh signature, app Clock one day ahead");
    assertEquals("PAID", data.status(order.orderId()), "status");
  }

  @Test
  void theWebhookNeedsNoCsrfToken() throws Exception {
    Placed order = order();
    Response response = send(paid("evt_giro011_paid_1", order));
    assertNotEquals(400, response.statusCode(), "the CSRF filter rejected the webhook");
    assertEquals(200, response.statusCode(), "status; body: " + body(response));
    assertEquals("PAID", data.status(order.orderId()), "status");
  }

  // --- isolation ------------------------------------------------------------------------------

  @Test
  void anEventNeverTouchesAnotherShopsOrder() throws Exception {
    Placed order = order();
    String paidObject = paidSession(order, order.sessionId(), PI);

    sendOk(event("evt_giro011_marco_1", COMPLETED, MARCO_ACCOUNT, paidObject), "Marco's account");
    assertEquals("PENDING_PAYMENT", data.status(order.orderId()), "after Marco's account");

    sendOk(
        event("evt_giro011_unknown_1", COMPLETED, "acct_1Sconosciuto", paidObject),
        "unknown account");
    assertEquals("PENDING_PAYMENT", data.status(order.orderId()), "after an unknown account");
    assertNotNull(
        data.stripeEvent("evt_giro011_unknown_1").get("processed_at"),
        "unknown account: processed_at");

    sendOk(event("evt_giro011_noaccount_1", COMPLETED, null, paidObject), "no account");
    assertEquals("PENDING_PAYMENT", data.status(order.orderId()), "after no account");

    String otherTenant =
        Giro011Webhooks.session(
            order.sessionId(), order.orderId(), MARCO_ID, order.number(), "complete", "paid", PI);
    sendOk(
        event("evt_giro011_tenant_1", COMPLETED, DEMO_ACCOUNT, otherTenant),
        "demo account, metadata.tenant_id of Marco");
    assertEquals("PENDING_PAYMENT", data.status(order.orderId()), "after metadata of Marco");

    String valid = paid("evt_giro011_paid_1", order);
    for (String host : List.of(DEMO, MARCO)) {
      Response response = post(host, valid, signature(valid));
      assertEquals(404, response.statusCode(), "webhook on the shop host " + host);
    }
    assertNull(data.stripeEvent("evt_giro011_paid_1"), "stripe_event written from a shop host");
    assertEquals("PENDING_PAYMENT", data.status(order.orderId()), "after shop hosts");
    assertEquals(1, data.events(order.orderId()).size(), "order events of the demo");

    // Positive control: Marco's own order with Marco's account is paid; the demo is not.
    Giro008Browser marco = new Giro008Browser(MARCO);
    marco.page("/prodotti/bouquet-di-stagione", 200);
    marco.addOk(data.checkout.cart.marco, 1);
    Placed marcoOrder = Giro011Webhooks.checkout(marco, data.checkout, stripe, MARCO_ID);
    sendOk(
        event(
            "evt_giro011_marco_2",
            COMPLETED,
            MARCO_ACCOUNT,
            Giro011Webhooks.session(
                marcoOrder.sessionId(),
                marcoOrder.orderId(),
                MARCO_ID,
                marcoOrder.number(),
                "complete",
                "paid",
                "pi_giro011_marco_1")),
        "Marco's paid event");
    assertEquals("PAID", data.status(marcoOrder.orderId()), "Marco's order");
    assertEquals("PENDING_PAYMENT", data.status(order.orderId()), "demo order after Marco's");
  }

  @Test
  void aSuspendedShopStillGetsItsPayment() throws Exception {
    Placed order = order();
    data.setTenantStatus(DEMO_ID, "SUSPENDED");
    sendOk(paid("evt_giro011_paid_1", order), "paid on a suspended shop");
    assertEquals("PAID", data.status(order.orderId()), "status");
  }
}
