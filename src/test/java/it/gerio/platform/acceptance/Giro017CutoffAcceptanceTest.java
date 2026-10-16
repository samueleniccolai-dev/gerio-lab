package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro008Browser.body;
import static it.gerio.platform.acceptance.Giro017Checkout.CUTOFF;
import static it.gerio.platform.acceptance.Giro017Checkout.GENERIC_DATE;
import static it.gerio.platform.acceptance.Giro017Checkout.assertRejected;
import static it.gerio.platform.acceptance.Giro017Checkout.delivery;
import static it.gerio.platform.acceptance.Giro017Checkout.minDate;
import static it.gerio.platform.acceptance.Giro017Checkout.pickup;
import static it.gerio.platform.acceptance.Giro017Checkout.roses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 017 (goal T7): the demo shop takes orders for today only before its cutoff, 12:00 in Rome,
 * for pickup and delivery alike (docs/lab/giri/017-piano.md). Tuesday 20/10/2026 is summer time
 * (UTC+2); Monday 26/10/2026 is winter time (UTC+1): no fixed offset.
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro017CutoffAcceptanceTest {

  static final String TODAY = "2026-10-20";
  static final String TOMORROW = "2026-10-21";
  static final String YESTERDAY = "2026-10-19";
  static final Instant BEFORE = Instant.parse("2026-10-20T09:59:00Z"); // 11:59 in Rome
  static final Instant AT = Instant.parse("2026-10-20T10:00:00Z"); // 12:00 in Rome

  @Inject DataSource dataSource;

  private Giro017Data data;
  private Giro010FakeStripe stripe;
  private final TestClock clock = new TestClock(BEFORE);

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

  private Map<String, Map<String, String>> today(String date) {
    Map<String, Map<String, String>> forms = new LinkedHashMap<>();
    forms.put("pickup", pickup(date));
    forms.put("delivery", delivery(date, "55100", "Lucca"));
    return forms;
  }

  @Test
  void at1159TodayIsStillOpenForPickupAndDelivery() throws SQLException {
    clock.set(BEFORE);
    int expected = 0;
    for (Map.Entry<String, Map<String, String>> c : today(TODAY).entrySet()) {
      Giro008Browser browser = roses(data.checkout());
      assertEquals(TODAY, minDate(browser.page("/checkout", 200)), c.getKey() + ": min at 11:59");
      Response response = browser.post("/checkout", c.getValue());
      expected++;
      assertEquals(303, response.statusCode(), c.getKey() + " at 11:59; body: " + body(response));
      assertEquals(expected, data.countOrders(), c.getKey() + ": orders");
    }
    assertEquals(
        "DELIVERY",
        data.orders(Giro005DemoData.DEMO_ID).get(1).get("fulfillment_type"),
        "the second order is a delivery");
  }

  @Test
  void at1200TodayIsClosedWithTheCutoffMessage() throws SQLException {
    clock.set(AT);
    for (Map.Entry<String, Map<String, String>> c : today(TODAY).entrySet()) {
      Giro008Browser browser = roses(data.checkout());
      assertEquals(TOMORROW, minDate(browser.page("/checkout", 200)), c.getKey() + ": min at 12");
      String html = assertRejected(browser.post("/checkout", c.getValue()), CUTOFF, c.getKey());
      assertFalse(html.contains(GENERIC_DATE), c.getKey() + ": the generic date message wins");
    }
    assertEquals(0, data.countOrders(), "orders for today after the cutoff");
    assertEquals(0, stripe.allCalls().size(), "Stripe was called");
  }

  @Test
  void at1200TomorrowIsOpenForPickupAndDelivery() throws SQLException {
    clock.set(AT);
    int expected = 0;
    for (Map.Entry<String, Map<String, String>> c : today(TOMORROW).entrySet()) {
      Giro008Browser browser = roses(data.checkout());
      Response response = browser.post("/checkout", c.getValue());
      expected++;
      assertEquals(303, response.statusCode(), c.getKey() + " tomorrow; body: " + body(response));
      assertEquals(expected, data.countOrders(), c.getKey() + ": orders");
    }
    assertEquals(
        "DELIVERY",
        data.orders(Giro005DemoData.DEMO_ID).get(1).get("fulfillment_type"),
        "the second order is a delivery");
  }

  @Test
  void aPastDateKeepsTheGenericMessageNotTheCutoff() throws SQLException {
    clock.set(AT);
    Giro008Browser browser = roses(data.checkout());
    for (Map.Entry<String, Map<String, String>> c : today(YESTERDAY).entrySet()) {
      String html =
          assertRejected(browser.post("/checkout", c.getValue()), GENERIC_DATE, c.getKey());
      assertFalse(html.contains(CUTOFF), c.getKey() + ": cutoff message for a past date");
    }
    assertEquals(0, data.countOrders(), "orders for a past date");
  }

  @Test
  void afterTheChangeToWinterTimeTheCutoffIsStill1200InRome() throws SQLException {
    String monday = "2026-10-26";
    clock.set(Instant.parse("2026-10-26T10:59:00Z")); // 11:59 in Rome, UTC+1
    Giro008Browser open = roses(data.checkout());
    assertEquals(monday, minDate(open.page("/checkout", 200)), "min at 11:59 of 26/10");
    Response ok = open.post("/checkout", delivery(monday, "55100", "Lucca"));
    assertEquals(303, ok.statusCode(), "11:59 of 26/10; body: " + body(ok));
    assertEquals(
        "DELIVERY",
        data.orders(Giro005DemoData.DEMO_ID).get(0).get("fulfillment_type"),
        "a delivery for 26/10");

    clock.set(Instant.parse("2026-10-26T11:00:00Z")); // 12:00 in Rome
    Giro008Browser closed = roses(data.checkout());
    assertEquals("2026-10-27", minDate(closed.page("/checkout", 200)), "min at 12:00 of 26/10");
    assertRejected(closed.post("/checkout", pickup(monday)), CUTOFF, "12:00 of 26/10");
    assertEquals(1, data.countOrders(), "orders after the cutoff of 26/10");
  }
}
