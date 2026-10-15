package it.gerio.platform.acceptance;

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
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 012: without a value for {@code app.demo.stripe-account-id} the demo stays as it is: a new
 * demo has no account (summary with phone and email, no payment, as in behaviour n. 23); a demo
 * already connected keeps account and charges flag (the value is changed only by setting a new
 * one). The property reads {@code DEMO_STRIPE_ACCOUNT_ID} (docs/lab/giri/012-piano.md).
 */
@QuarkusTest
@TestProfile(Giro012Profiles.WithoutAccount.class)
class Giro012DemoWithoutAccountAcceptanceTest {

  private static final Path PROPERTIES =
      Path.of("src", "main", "resources", "application.properties");

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

  @Test
  void withoutAValueANewDemoStaysWithoutPayment() throws Exception {
    data.connect(DEMO_ID, null, false);
    data.restart();

    assertEquals(new Giro012Data.Stripe(null, false), data.stripe(DEMO_ID), "demo");
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data.checkout());
    String summary = browser.page("/checkout", 200);
    assertFalse(summary.contains(PAY), "\"" + PAY + "\" shown without an account");
    assertTrue(summary.contains("+39 0583 000000"), "the shop's phone is not shown");
    assertTrue(summary.contains("ciao@glicine.example"), "the shop's email is not shown");

    Response response = browser.post("/checkout", form(Giro010CheckoutAcceptanceTest.TOMORROW));
    assertEquals(409, response.statusCode(), "POST /checkout without an account");
    assertEquals(0, data.checkout().countOrders(), "orders");
    assertEquals(0, stripe.allCalls().size(), "calls to Stripe");
  }

  @Test
  void removingTheValueKeepsTheAccountAndItsFlag() throws Exception {
    data.connect(DEMO_ID, ACCOUNT, true);
    data.restart();

    assertEquals(new Giro012Data.Stripe(ACCOUNT, true), data.stripe(DEMO_ID), "demo");
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data.checkout());
    assertTrue(browser.page("/checkout", 200).contains(PAY), "\"" + PAY + "\" missing");
  }

  @Test
  void theAccountComesOnlyFromTheEnvironment() throws Exception {
    List<String> lines =
        Files.readAllLines(PROPERTIES, StandardCharsets.UTF_8).stream()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .filter(line -> line.contains("app.demo.stripe-account-id"))
            .toList();
    assertEquals(
        List.of("app.demo.stripe-account-id=${DEMO_STRIPE_ACCOUNT_ID:}"),
        lines,
        "app.demo.stripe-account-id in application.properties");
  }
}
