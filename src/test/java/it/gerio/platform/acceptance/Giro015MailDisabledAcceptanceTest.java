package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.paidSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.placeDemoOrder;
import static it.gerio.platform.acceptance.Giro011Webhooks.sendOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import it.gerio.platform.acceptance.Giro011Webhooks.Placed;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 015: with sending switched off ({@code app.mail.enabled=false}, the production default until
 * a provider exists) the worker sends nothing, leaves the row PENDING without counting an attempt,
 * and writes no personal data to the log (docs/lab/giri/015-piano.md).
 */
@QuarkusTest
@TestProfile(Giro015MailDisabledAcceptanceTest.MailDisabled.class)
class Giro015MailDisabledAcceptanceTest {

  /** Mock mailer on (to count mails), sending off, application log in its own file. */
  public static class MailDisabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> config = new LinkedHashMap<>();
      config.put("app.mail.enabled", "false");
      config.put("quarkus.log.file.enabled", "true");
      config.put(
          "quarkus.log.file.path", "target/giro015-mail-disabled-" + System.nanoTime() + ".log");
      config.put("quarkus.log.file.format", "%p %c %s%e%n");
      return config;
    }

    @Override
    public List<TestResourceEntry> testResources() {
      return List.of(new TestResourceEntry(Giro011FakeStripe.class));
    }
  }

  @Inject DataSource dataSource;

  private Giro015Data data;
  private Giro010FakeStripe stripe;
  private final TestClock clock =
      new TestClock(Giro015ConfirmationEmailAcceptanceTest.LATE_EVENING_UTC);

  @BeforeEach
  void setUp() throws Exception {
    QuarkusMock.installMockForType(clock, Clock.class);
    stripe = Giro010FakeStripe.get();
    stripe.reset();
    data = new Giro015Data(dataSource);
    Giro015Mail.clear();
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  @Test
  void nothingIsSentTheRowWaitsAndTheLogHasNoPersonalData() throws Exception {
    Placed order = placeDemoOrder(data.giro014.giro011.checkout, stripe);
    int mark = Giro012Log.mark();
    sendOk(
        event(
            "evt_giro015_off_1",
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(order, order.sessionId(), "pi_g15_off")),
        "paid");

    Giro015Mail.drainOnce();
    Giro015Mail.drainOnce();

    assertEquals(0, Giro015Mail.total(), "mails sent with sending off");
    Map<String, Object> row = data.onlyRow(order.orderId());
    assertEquals("PENDING", row.get("status"), "row status with sending off");
    assertEquals(0, ((Number) row.get("attempts")).intValue(), "attempts with sending off");

    List<String> lines = Giro012Log.since(mark);
    for (String line : lines) {
      for (String personal : List.of(Giro010Checkout.EMAIL, "Giulia", "Bianchi", "333 000 1234")) {
        assertFalse(line.contains(personal), "the log shows personal data: " + line);
      }
    }
  }
}
