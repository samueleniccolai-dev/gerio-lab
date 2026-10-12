package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro008Browser.body;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro010Checkout.assertLine;
import static it.gerio.platform.acceptance.Giro010Checkout.assertToStripe;
import static it.gerio.platform.acceptance.Giro010Checkout.fillDemoCart;
import static it.gerio.platform.acceptance.Giro010Checkout.form;
import static it.gerio.platform.acceptance.Giro010Checkout.lineItems;
import static it.gerio.platform.acceptance.Giro010CheckoutAcceptanceTest.numbers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Giro 010 (goal T5, part 1): repeated and parallel "Ordina e paga", a changed cart, a failing
 * Stripe, and the dates and numbers that depend on the day in Europe/Rome. Black box over HTTP;
 * Stripe is the fake of {@link Giro010FakeStripe}, which replays answers per idempotency key like
 * Stripe does (docs/lab/giri/010-piano.md, critique points 1-4).
 */
@QuarkusTest
@WithTestResource(Giro010FakeStripe.class)
class Giro010CheckoutRetryAcceptanceTest {

  private static final Instant T0 = Giro010CheckoutAcceptanceTest.T0;
  private static final String TOMORROW = Giro010CheckoutAcceptanceTest.TOMORROW;

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
  void aSecondPostWithTheSameCartReusesTheOpenSessionWithoutCallingStripe() throws SQLException {
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);
    browser.page("/checkout", 200);
    Response first = browser.post("/checkout", form(TOMORROW));
    assertEquals(1, stripe.createdSessions().size(), "sessions after the first POST");
    String session = stripe.createdSessions().get(0);
    assertToStripe(first, session, "first POST");

    browser.page("/checkout", 200);
    Response second = browser.post("/checkout", form(TOMORROW));
    assertToStripe(second, session, "second POST with the same cart");
    assertEquals(1, stripe.sessionCalls().size(), "the second POST called Stripe again");
    assertEquals(1, data.orders(DEMO_ID).size(), "orders after two POSTs");
  }

  @Test
  void twoParallelPostsMakeOneOrderOneSessionAndTheSameRedirect() throws Exception {
    stripe.delay(500);
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);
    browser.page("/checkout", 200);
    Map<String, String> cookies = new LinkedHashMap<>(browser.cookies);
    Map<String, String> formWithToken = browser.withToken(form(TOMORROW));

    int n = 2;
    CyclicBarrier barrier = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    List<Future<Response>> results = new ArrayList<>();
    try {
      for (int i = 0; i < n; i++) {
        Giro008Browser thread = new Giro008Browser(DEMO);
        results.add(
            pool.submit(
                () -> {
                  barrier.await(30, TimeUnit.SECONDS);
                  return thread.postRaw("/checkout", formWithToken, cookies);
                }));
      }
      List<Response> responses = new ArrayList<>();
      for (Future<Response> result : results) {
        responses.add(result.get(60, TimeUnit.SECONDS));
      }
      assertEquals(1, stripe.createdSessions().size(), "sessions created by two parallel POSTs");
      String session = stripe.createdSessions().get(0);
      for (Response response : responses) {
        assertToStripe(response, session, "parallel POST");
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, data.orders(DEMO_ID).size(), "orders after two parallel POSTs");
  }

  @Test
  void aChangedCartAfterTheFirstAttemptGetsANewOrderAndANewSession() throws SQLException {
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);
    browser.page("/checkout", 200);
    Response first = browser.post("/checkout", form(TOMORROW));
    assertEquals(1, stripe.createdSessions().size(), "sessions after the first POST");
    assertToStripe(first, stripe.createdSessions().get(0), "first POST");

    // Back from Stripe without paying, the customer adds one more orchid.
    browser.addOk(data.cart.orchid, 1);
    browser.page("/checkout", 200);
    Response second = browser.post("/checkout", form(TOMORROW));

    assertEquals(2, stripe.createdSessions().size(), "sessions after the changed cart");
    assertToStripe(second, stripe.createdSessions().get(1), "POST after the change");
    List<Giro010FakeStripe.Call> calls = stripe.sessionCalls();
    assertNotEquals(
        calls.get(0).idempotencyKey(),
        calls.get(calls.size() - 1).idempotencyKey(),
        "the changed cart reused the idempotency key");
    List<Map<String, String>> lines = lineItems(calls.get(calls.size() - 1).form());
    assertLine(lines, "Mazzo di rose rosse", "12 rose", 4500, 1);
    assertLine(lines, "Orchidea Phalaenopsis bianca", "Vaso 12 cm", 2990, 3);

    List<Map<String, Object>> orders = data.orders(DEMO_ID);
    assertEquals(List.of("2026-0001", "2026-0002"), numbers(orders), "order numbers");
    assertEquals(10480L, ((Number) orders.get(0).get("total_cents")).longValue(), "first total");
    assertEquals(13470L, ((Number) orders.get(1).get("total_cents")).longValue(), "new total");
    assertEquals(
        "order-" + ((Number) orders.get(1).get("id")).longValue() + "-attempt-1",
        calls.get(calls.size() - 1).idempotencyKey(),
        "idempotency key of the new order");
  }

  @Test
  void aStripeErrorKeepsTheOrderShowsAnErrorAndTheNextTryUsesAttemptTwo() throws SQLException {
    stripe.failNewKeys(1);
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);
    browser.page("/checkout", 200);

    Response failed = browser.post("/checkout", form(TOMORROW));
    assertEquals(502, failed.statusCode(), "POST /checkout when Stripe answers 500");
    assertTrue(body(failed).contains("Riprova"), "the error page does not say \"Riprova\"");
    assertEquals(0, stripe.createdSessions().size(), "sessions after the error");
    List<Map<String, Object>> orders = data.orders(DEMO_ID);
    assertEquals(1, orders.size(), "orders after the error");
    assertEquals("PENDING_PAYMENT", orders.get(0).get("status"), "status after the error");
    long orderId = ((Number) orders.get(0).get("id")).longValue();
    for (Giro010FakeStripe.Call call : stripe.sessionCalls()) {
      assertEquals("order-" + orderId + "-attempt-1", call.idempotencyKey(), "first try key");
    }

    browser.page("/checkout", 200);
    Response retry = browser.post("/checkout", form(TOMORROW));
    assertEquals(1, stripe.createdSessions().size(), "sessions after the retry");
    assertToStripe(retry, stripe.createdSessions().get(0), "retry");
    List<Giro010FakeStripe.Call> calls = stripe.sessionCalls();
    assertEquals(
        "order-" + orderId + "-attempt-2",
        calls.get(calls.size() - 1).idempotencyKey(),
        "key of the retry");
    assertEquals(1, data.orders(DEMO_ID).size(), "orders after the retry");
    assertEquals(
        stripe.createdSessions().get(0),
        data.orders(DEMO_ID).get(0).get("stripe_checkout_session_id"),
        "stored session id after the retry");
  }

  @Test
  void theFirstOrdersOfTheYearInParallelGetDistinctNumbers() throws Exception {
    int n = 2;
    List<Giro008Browser> browsers = new ArrayList<>();
    List<Map<String, String>> forms = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Giro008Browser browser = new Giro008Browser(DEMO);
      browser.page("/prodotti/orchidea-bianca", 200);
      browser.addOk(data.cart.orchid, i + 1);
      browser.page("/checkout", 200);
      browsers.add(browser);
      forms.add(browser.withToken(form(TOMORROW)));
    }

    CyclicBarrier barrier = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    List<Future<Integer>> results = new ArrayList<>();
    try {
      for (int i = 0; i < n; i++) {
        Giro008Browser browser = browsers.get(i);
        Map<String, String> sent = forms.get(i);
        Map<String, String> cookies = new LinkedHashMap<>(browser.cookies);
        results.add(
            pool.submit(
                () -> {
                  barrier.await(30, TimeUnit.SECONDS);
                  return browser.postRaw("/checkout", sent, cookies).statusCode();
                }));
      }
      for (Future<Integer> result : results) {
        assertEquals(303, result.get(60, TimeUnit.SECONDS), "status of a parallel first order");
      }
    } finally {
      pool.shutdownNow();
    }
    Set<Object> found = new HashSet<>(numbers(data.orders(DEMO_ID)));
    assertEquals(Set.of("2026-0001", "2026-0002"), found, "numbers of the first two orders");
  }

  @Test
  void theYearOfTheNumberIsTheYearInRome() throws SQLException {
    clock.set(Instant.parse("2026-12-31T23:30:00Z")); // 1 January 2027, 00:30 in Rome
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);
    browser.page("/checkout", 200);
    Response response = browser.post("/checkout", form("2027-01-01"));
    assertEquals(1, stripe.createdSessions().size(), "sessions");
    assertToStripe(response, stripe.createdSessions().get(0), "POST on new year's night");
    assertEquals(List.of("2027-0001"), numbers(data.orders(DEMO_ID)), "number");
    assertEquals(
        "2027-0001", stripe.sessionCalls().get(0).form().get("metadata[order_number]"), "metadata");
  }

  @Test
  void todayForThePickupDateIsTodayInRome() throws SQLException {
    clock.set(Instant.parse("2026-10-12T22:30:00Z")); // 13 October, 00:30 in Rome
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);
    browser.page("/checkout", 200);

    Response yesterday = browser.post("/checkout", form("2026-10-12"));
    assertEquals(400, yesterday.statusCode(), "pickup on 12/10 when in Rome it is already 13/10");
    assertEquals(0, data.countOrders(), "orders after the past date");
    assertEquals(0, stripe.allCalls().size(), "Stripe called for a past date");

    Response today = browser.post("/checkout", form("2026-10-13"));
    assertEquals(1, stripe.createdSessions().size(), "sessions");
    assertToStripe(today, stripe.createdSessions().get(0), "pickup today in Rome");
    assertEquals(
        "2026-10-13",
        String.valueOf(data.orders(DEMO_ID).get(0).get("delivery_date")),
        "stored pickup date");
  }
}
