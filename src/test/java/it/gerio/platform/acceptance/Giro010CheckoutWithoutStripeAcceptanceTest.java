package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro010Checkout.PAY;
import static it.gerio.platform.acceptance.Giro010Checkout.fillDemoCart;
import static it.gerio.platform.acceptance.Giro010Checkout.form;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 010 (goal T5, part 1, critique point 5): the app starts without any Stripe key, as on the
 * server until the giro 012, and the checkout is simply off. Runs in the plain test profile: no
 * {@code app.stripe.secret-key}, no fake Stripe. The demo shop has a connected account with charges
 * enabled, so only the missing key can turn the payment off.
 */
@QuarkusTest
class Giro010CheckoutWithoutStripeAcceptanceTest {

  @Inject DataSource dataSource;

  private Giro010CheckoutData data;

  @BeforeEach
  void setUp() throws IOException, SQLException {
    data = new Giro010CheckoutData(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  @Test
  void withoutAKeyTheSummaryHasNoPayButtonAndThePostCreatesNoOrder() throws SQLException {
    Giro008Browser browser = new Giro008Browser(DEMO);
    fillDemoCart(browser, data);

    String html = browser.page("/checkout", 200);
    assertTrue(html.contains("104,80" + Giro008Browser.NBSP + "€"), "summary without the total");
    assertFalse(html.contains(PAY), "\"" + PAY + "\" shown without a Stripe key");
    assertTrue(html.contains("+39 0583 000000"), "the shop's phone is not shown");

    String tomorrow = LocalDate.now(ZoneId.of("Europe/Rome")).plusDays(1).toString();
    Response response = browser.post("/checkout", form(tomorrow));
    assertEquals(409, response.statusCode(), "POST /checkout without a Stripe key");
    assertEquals(0, data.countOrders(), "orders created without a Stripe key");
  }
}
