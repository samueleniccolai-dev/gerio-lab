package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro010Checkout.assertLine;
import static it.gerio.platform.acceptance.Giro010Checkout.fillDemoCart;
import static it.gerio.platform.acceptance.Giro010Checkout.lineItems;
import static it.gerio.platform.acceptance.Giro010CheckoutAcceptanceTest.numbers;
import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.paidSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.placeDemoOrder;
import static it.gerio.platform.acceptance.Giro011Webhooks.sendOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import it.gerio.platform.acceptance.Giro011Webhooks.Placed;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 011: a changed price or a paid order never lets an old order be reused (doubt 1 of the giro
 * 010 reviewer). Rose 4500 → 4800: new total 10780 (docs/lab/giri/011-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro011CheckoutPriceChangeAcceptanceTest {

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

  @Test
  void aNewPriceBetweenTwoAttemptsMakesANewOrderWithTheNewPrice() throws Exception {
    Placed first = placeDemoOrder(data.checkout, stripe);
    data.checkout.setPrice(data.checkout.rose12, 4800);

    Placed second = Giro011Webhooks.checkout(first.browser(), data.checkout, stripe, DEMO_ID);

    assertNotEquals(first.orderId(), second.orderId(), "the order with the old price was reused");
    List<Map<String, Object>> orders = data.checkout.orders(DEMO_ID);
    assertEquals(List.of("2026-0001", "2026-0002"), numbers(orders), "order numbers");
    assertEquals(10480L, ((Number) orders.get(0).get("total_cents")).longValue(), "first total");
    assertEquals(10780L, ((Number) orders.get(1).get("total_cents")).longValue(), "new total");
    List<Giro010FakeStripe.Call> calls = stripe.sessionCalls();
    Giro010FakeStripe.Call last = calls.get(calls.size() - 1);
    assertLine(lineItems(last.form()), "Mazzo di rose rosse", "12 rose", 4800, 1);
    assertEquals(
        "order-" + second.orderId() + "-attempt-1", last.idempotencyKey(), "idempotency key");
  }

  @Test
  void aPaidOrderIsNeverReusedByTheNextCheckout() throws Exception {
    Placed first = placeDemoOrder(data.checkout, stripe);
    sendOk(
        event(
            "evt_giro011_paid_1",
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(first, first.sessionId(), "pi_giro011_demo_1")),
        "paid");
    assertEquals("PAID", data.status(first.orderId()), "first order");

    // Same browser, cart emptied by the payment: the customer fills it again and pays.
    fillDemoCart(first.browser(), data.checkout);
    Placed second = Giro011Webhooks.checkout(first.browser(), data.checkout, stripe, DEMO_ID);

    assertNotEquals(first.orderId(), second.orderId(), "the paid order was reused");
    assertEquals(
        List.of("2026-0001", "2026-0002"), numbers(data.checkout.orders(DEMO_ID)), "order numbers");
    assertEquals("PAID", data.status(first.orderId()), "first order after the new checkout");
    assertEquals("PENDING_PAYMENT", data.status(second.orderId()), "new order");
  }
}
