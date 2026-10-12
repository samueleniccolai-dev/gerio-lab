package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro008Browser.body;
import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.send;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 011: without the webhook signing secret (the default test configuration, like giro 010's
 * test without Stripe) the webhook answers 503 and writes nothing, so Stripe retries later.
 */
@QuarkusTest
class Giro011WebhookWithoutSecretAcceptanceTest {

  @Inject DataSource dataSource;

  private Giro011Data data;

  @BeforeEach
  void setUp() throws Exception {
    data = new Giro011Data(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  @Test
  void withoutTheSecretTheWebhookAnswers503AndWritesNothing() throws SQLException {
    String object =
        Giro011Webhooks.session(
            "cs_test_giro011_nosecret",
            1,
            Giro005DemoData.DEMO_ID,
            "2026-0001",
            "complete",
            "paid",
            "pi_giro011_demo_1");
    Response response = send(event("evt_giro011_nosecret_1", COMPLETED, DEMO_ACCOUNT, object));
    assertEquals(503, response.statusCode(), "status without secret; body: " + body(response));
    assertEquals(0, data.countStripeEvents(), "stripe_event rows");
  }
}
