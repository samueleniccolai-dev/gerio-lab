package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro010Checkout.PAY;
import static it.gerio.platform.acceptance.Giro010Checkout.fillDemoCart;
import static it.gerio.platform.acceptance.Giro010Checkout.form;
import static it.gerio.platform.acceptance.Giro012Profiles.ACCOUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusMock;
import io.restassured.response.Response;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 012: a live Stripe key ({@code sk_live_…} or {@code rk_live_…}) is refused. The application
 * starts, the connected demo has no payment ({@code POST /checkout} 409, nothing sent to Stripe)
 * and the log says why, without showing the key. Run by one subclass per kind of key.
 */
abstract class Giro012LiveKeyRefused {

  @Inject DataSource dataSource;

  private Giro012Data data;
  private Giro010FakeStripe stripe;

  @BeforeEach
  void setUp() throws Exception {
    QuarkusMock.installMockForType(new TestClock(Giro010CheckoutAcceptanceTest.T0), Clock.class);
    stripe = Giro010FakeStripe.get();
    stripe.reset();
    data = new Giro012Data(dataSource);
    // Already connected to the account of the profile: only the key can turn the payment off.
    data.connect(DEMO_ID, ACCOUNT, true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  @Test
  void aLiveKeyTurnsTheCheckoutOffAndTheLogSaysWhyWithoutTheKey() throws Exception {
    data.restart();
    assertEquals(
        new Giro012Data.Stripe(ACCOUNT, true), data.stripe(DEMO_ID), "the demo is connected");

    given().header("Host", "localhost").when().get("/q/health/ready").then().statusCode(200);
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data.checkout());
    String summary = browser.page("/checkout", 200);
    assertFalse(summary.contains(PAY), "\"" + PAY + "\" shown with a live key");
    assertTrue(summary.contains("+39 0583 000000"), "the shop's phone is not shown");

    Response response = browser.post("/checkout", form(Giro010CheckoutAcceptanceTest.TOMORROW));
    assertEquals(409, response.statusCode(), "POST /checkout with a live key");
    assertEquals(0, data.checkout().countOrders(), "orders created with a live key");
    assertEquals(0, stripe.allCalls().size(), "calls to Stripe with a live key");

    Giro012Log.assertLine(Giro012Log.lines(), "live", "live key refused", "WARN", "ERROR");
    Giro012Log.assertNoKey();
  }
}
