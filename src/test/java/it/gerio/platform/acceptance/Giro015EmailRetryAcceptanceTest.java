package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.paidSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.placeDemoOrder;
import static it.gerio.platform.acceptance.Giro011Webhooks.sendOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import it.gerio.platform.acceptance.Giro011Webhooks.Placed;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 015: an SMTP server that refuses the connection leaves the row PENDING with growing waits
 * (1, 2, 4… minutes from the clock), a row not yet due is not taken, and the eighth failure marks
 * it FAILED (docs/lab/giri/015-piano.md).
 */
@QuarkusTest
@TestProfile(Giro015EmailRetryAcceptanceTest.RefusingSmtp.class)
class Giro015EmailRetryAcceptanceTest {

  /** The real mailer, pointed at a local port where nobody listens. */
  public static class RefusingSmtp implements QuarkusTestProfile {

    private static final int CLOSED_PORT = closedPort();

    private static int closedPort() {
      try (ServerSocket socket = new ServerSocket(0)) {
        return socket.getLocalPort();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.mailer.mock", "false",
          "quarkus.mailer.host", "127.0.0.1",
          "quarkus.mailer.port", String.valueOf(CLOSED_PORT),
          "app.mail.enabled", "true");
    }

    @Override
    public List<TestResourceEntry> testResources() {
      return List.of(new TestResourceEntry(Giro011FakeStripe.class));
    }
  }

  private static final Instant T0 = Giro015ConfirmationEmailAcceptanceTest.LATE_EVENING_UTC;

  @Inject DataSource dataSource;

  private Giro015Data data;
  private Giro010FakeStripe stripe;
  private final TestClock clock = new TestClock(T0);

  @BeforeEach
  void setUp() throws Exception {
    QuarkusMock.installMockForType(clock, Clock.class);
    stripe = Giro010FakeStripe.get();
    stripe.reset();
    data = new Giro015Data(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  private static Instant instant(Object timestamptz) {
    assertNotNull(timestamptz, "next_attempt_at is null");
    return ((Timestamp) timestamptz).toInstant();
  }

  private static int attempts(Map<String, Object> row) {
    return ((Number) row.get("attempts")).intValue();
  }

  @Test
  void failuresWaitOneTwoFourMinutesAndTheEighthMarksFailed() throws Exception {
    Placed order = placeDemoOrder(data.giro014.giro011.checkout, stripe);
    sendOk(
        event(
            "evt_giro015_retry_1",
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(order, order.sessionId(), "pi_g15_retry")),
        "paid");
    Map<String, Object> row = data.onlyRow(order.orderId());
    assertEquals("PENDING", row.get("status"), "new row");
    assertEquals(0, attempts(row), "attempts of a new row");

    // First failure at T0: next attempt at T0 + 1 minute.
    Giro015Mail.drainOnce();
    row = data.onlyRow(order.orderId());
    assertEquals("PENDING", row.get("status"), "after the first failure");
    assertEquals(1, attempts(row), "attempts after the first failure");
    assertEquals(T0.plus(Duration.ofMinutes(1)), instant(row.get("next_attempt_at")), "next 1");
    assertNotNull(row.get("last_error"), "last_error after the first failure");

    // Not due yet: another pass at the same instant does not take the row.
    clock.set(T0.plusSeconds(59));
    Giro015Mail.drainOnce();
    row = data.onlyRow(order.orderId());
    assertEquals(1, attempts(row), "a row not yet due was taken");
    assertEquals(T0.plus(Duration.ofMinutes(1)), instant(row.get("next_attempt_at")), "unchanged");

    // Attempts 2..7: each one waits twice as long as the one before.
    Instant now = T0.plus(Duration.ofMinutes(1));
    for (int attempt = 2; attempt <= 7; attempt++) {
      clock.set(now);
      Giro015Mail.drainOnce();
      row = data.onlyRow(order.orderId());
      assertEquals("PENDING", row.get("status"), "after failure " + attempt);
      assertEquals(attempt, attempts(row), "attempts after failure " + attempt);
      Instant expected = now.plus(Duration.ofMinutes(1L << (attempt - 1)));
      assertEquals(expected, instant(row.get("next_attempt_at")), "next after failure " + attempt);
      now = expected;
    }

    // Eighth failure: FAILED, and no more attempts later.
    clock.set(now);
    Giro015Mail.drainOnce();
    row = data.onlyRow(order.orderId());
    assertEquals("FAILED", row.get("status"), "after the eighth failure");
    assertEquals(8, attempts(row), "attempts after the eighth failure");
    assertNotNull(row.get("last_error"), "last_error after the eighth failure");

    clock.set(now.plus(Duration.ofDays(1)));
    Giro015Mail.drainOnce();
    assertEquals(8, attempts(data.onlyRow(order.orderId())), "a FAILED row was tried again");
  }
}
