package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro005DemoData.MARCO_ID;
import static it.gerio.platform.acceptance.Giro008CartData.MARCO;
import static it.gerio.platform.acceptance.Giro008CartData.PLATFORM;
import static it.gerio.platform.acceptance.Giro010CheckoutData.MARCO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.session;
import static it.gerio.platform.acceptance.Giro012Profiles.ACCOUNT;
import static it.gerio.platform.acceptance.Giro012Profiles.WEBHOOK_SECRET;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import it.gerio.platform.acceptance.Giro011Webhooks.Placed;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 012, isolation: the account of the seed goes only to the demo shop, and a webhook event of
 * that account changes only the demo's order, even when Marco's shop has an order with the same
 * number. Marco keeps his own account (docs/lab/giri/012-piano.md).
 */
@QuarkusTest
@TestProfile(Giro012Profiles.WithAccount.class)
class Giro012DemoStripeAccountIsolationAcceptanceTest {

  @Inject DataSource dataSource;

  private Giro012Data data;
  private Giro010FakeStripe stripe;

  @BeforeEach
  void setUp() throws Exception {
    QuarkusMock.installMockForType(new TestClock(Giro010CheckoutAcceptanceTest.T0), Clock.class);
    stripe = Giro010FakeStripe.get();
    stripe.reset();
    data = new Giro012Data(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  private static void sendOk(String payload, String what) {
    Response response =
        Giro011Webhooks.post(
            PLATFORM, payload, Giro011Webhooks.signature(payload, WEBHOOK_SECRET, Instant.now()));
    assertEquals(
        200, response.statusCode(), what + ": status; body: " + Giro008Browser.body(response));
  }

  @Test
  void anEventOfTheDemoAccountChangesOnlyTheDemoOrder() throws Exception {
    data.restart();
    assertEquals(new Giro012Data.Stripe(ACCOUNT, true), data.stripe(DEMO_ID), "demo");
    assertEquals(new Giro012Data.Stripe(MARCO_ACCOUNT, true), data.stripe(MARCO_ID), "Marco");

    Placed demo = Giro011Webhooks.placeDemoOrder(data.checkout(), stripe);
    Giro008Browser marcoBrowser = new Giro008Browser(MARCO);
    marcoBrowser.page("/prodotti/bouquet-di-stagione", 200);
    marcoBrowser.addOk(data.checkout().cart.marco, 1);
    Placed marco = Giro011Webhooks.checkout(marcoBrowser, data.checkout(), stripe, MARCO_ID);
    assertEquals(demo.number(), marco.number(), "precondition: same order number in both shops");
    assertEquals(
        ACCOUNT, stripe.sessionCalls().get(0).header("Stripe-Account"), "demo session account");
    assertEquals(
        MARCO_ACCOUNT,
        stripe.sessionCalls().get(1).header("Stripe-Account"),
        "Marco's session account");

    // The demo account, with the session and metadata of Marco's order: nothing changes.
    sendOk(
        event(
            "evt_giro012_marco_1",
            COMPLETED,
            ACCOUNT,
            session(
                marco.sessionId(),
                marco.orderId(),
                MARCO_ID,
                marco.number(),
                "complete",
                "paid",
                "pi_giro012_marco_1")),
        "demo account, Marco's order");
    assertEquals("PENDING_PAYMENT", data.webhooks.status(marco.orderId()), "Marco's order");
    assertEquals("PENDING_PAYMENT", data.webhooks.status(demo.orderId()), "demo order");

    // The demo account with the demo's order: only the demo's order is paid.
    sendOk(
        event(
            "evt_giro012_demo_1",
            COMPLETED,
            ACCOUNT,
            Giro011Webhooks.paidSession(demo, demo.sessionId(), "pi_giro012_demo_1")),
        "demo account, demo order");
    assertEquals("PAID", data.webhooks.status(demo.orderId()), "demo order");
    assertEquals(
        "PENDING_PAYMENT", data.webhooks.status(marco.orderId()), "Marco's order, same number");
    assertEquals(1, data.webhooks.events(marco.orderId()).size(), "events of Marco's order");
  }
}
