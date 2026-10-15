package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro005DemoData.MARCO_ID;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro010Checkout.PAY;
import static it.gerio.platform.acceptance.Giro010Checkout.fillDemoCart;
import static it.gerio.platform.acceptance.Giro010Checkout.form;
import static it.gerio.platform.acceptance.Giro012Profiles.ACCOUNT;
import static it.gerio.platform.acceptance.Giro012Profiles.TEST_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import it.gerio.platform.acceptance.Giro010FakeStripe.Call;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 012 (goal T5, part 3): with {@code app.demo.stripe-account-id=acct_1GlicineDemoTest0} and a
 * test key, the demo seed connects the demo shop (account and charges enabled) and the checkout
 * reaches Stripe on that account. A restart with the same value changes nothing; a new value
 * replaces the old account; an account already used by another shop leaves the demo as it was and
 * logs an error, without stopping the application (docs/lab/giri/012-piano.md).
 */
@QuarkusTest
@TestProfile(Giro012Profiles.WithAccount.class)
class Giro012DemoStripeAccountAcceptanceTest {

  private static final String OLD_ACCOUNT = "acct_1GlicineVecchio";

  @Inject DataSource dataSource;

  private Giro012Data data;
  private Giro010FakeStripe stripe;

  @BeforeEach
  void setUp() throws Exception {
    QuarkusMock.installMockForType(new TestClock(Giro010CheckoutAcceptanceTest.T0), Clock.class);
    stripe = Giro010FakeStripe.get();
    stripe.reset();
    data = new Giro012Data(dataSource);
    // A fresh demo shop, as on a new server: not connected.
    data.connect(DEMO_ID, null, false);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  @Test
  void theSeedConnectsTheDemoAndTheCheckoutUsesItsAccount() throws Exception {
    data.restart();

    assertEquals(
        new Giro012Data.Stripe(ACCOUNT, true),
        data.stripe(DEMO_ID),
        "demo after the seed: account and charges enabled");

    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data.checkout());
    String summary = browser.page("/checkout", 200);
    assertTrue(summary.contains(PAY), "\"" + PAY + "\" missing on the connected demo");

    Response response = browser.post("/checkout", form(Giro010CheckoutAcceptanceTest.TOMORROW));
    assertEquals(
        303, response.statusCode(), "POST /checkout; body: " + Giro008Browser.body(response));
    List<Call> calls = stripe.sessionCalls();
    assertEquals(1, calls.size(), "session requests to Stripe");
    assertEquals(ACCOUNT, calls.get(0).header("Stripe-Account"), "Stripe-Account header");
    assertEquals("Bearer " + TEST_KEY, calls.get(0).header("Authorization"), "test key used");
    Giro012Log.assertNoKey();
  }

  @Test
  void aRestartWithTheSameValueChangesNothing() throws Exception {
    int mark = Giro012Log.mark();
    data.restart();
    Giro012Data.Stripe first = data.stripe(DEMO_ID);
    data.restart();

    assertEquals(new Giro012Data.Stripe(ACCOUNT, true), first, "demo after the first restart");
    assertEquals(first, data.stripe(DEMO_ID), "demo after the second restart");
    assertEquals(1, data.countTenantsWithAccount(ACCOUNT), "shops with the demo account");
    assertEquals(
        new Giro012Data.Stripe(Giro010CheckoutData.MARCO_ACCOUNT, true),
        data.stripe(MARCO_ID),
        "Marco's shop");
    List<String> errors =
        Giro012Log.since(mark).stream().filter(l -> Giro012Log.isLevel(l, "ERROR")).toList();
    assertEquals(List.of(), errors, "errors logged by the restarts");
  }

  @Test
  void aNewValueReplacesTheOldAccountAndEnablesCharges() throws Exception {
    data.connect(DEMO_ID, OLD_ACCOUNT, false);
    data.restart();

    assertEquals(new Giro012Data.Stripe(ACCOUNT, true), data.stripe(DEMO_ID), "demo");
    assertEquals(0, data.countTenantsWithAccount(OLD_ACCOUNT), "shops with the old account");
  }

  @Test
  void anAccountOfAnotherShopKeepsTheDemoAsItWasAndLogsAnError() throws Exception {
    data.connect(DEMO_ID, OLD_ACCOUNT, true);
    data.connect(MARCO_ID, ACCOUNT, true);
    int mark = Giro012Log.mark();

    data.restart();

    assertEquals(
        new Giro012Data.Stripe(OLD_ACCOUNT, true), data.stripe(DEMO_ID), "demo keeps its account");
    assertEquals(
        new Giro012Data.Stripe(ACCOUNT, true), data.stripe(MARCO_ID), "Marco keeps his account");
    Giro012Log.assertLine(Giro012Log.since(mark), "stripe", "conflict on restart", "ERROR");
    Giro012Log.assertNoKey();

    String catalog = new Giro008Browser(DEMO).page("/catalogo", 200);
    assertTrue(catalog.contains(Giro012Data.DEMO_NAME), "the demo catalog after the conflict");
    assertFalse(catalog.contains("Glicine prima del riavvio"), "the demo seed did not run");
  }
}
