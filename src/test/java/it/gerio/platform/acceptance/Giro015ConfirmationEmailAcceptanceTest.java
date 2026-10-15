package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.EXPIRED;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.expiredSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.paidSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.placeDemoOrder;
import static it.gerio.platform.acceptance.Giro011Webhooks.sendOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import it.gerio.platform.acceptance.Giro011Webhooks.Placed;
import it.gerio.platform.acceptance.Giro015Mail.Sent;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 015: after the payment, the webhook queues the confirmation in {@code outbox_message} and
 * one pass of the outbox worker sends it to the customer (docs/lab/giri/015-piano.md). The mails
 * are read from the mock mailbox of {@code quarkus-mailer}.
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro015ConfirmationEmailAcceptanceTest {

  /** 00:30 of 12/10/2026 in Rome, still 11/10 in UTC: the order date must be the shop's. */
  static final Instant LATE_EVENING_UTC = Instant.parse("2026-10-11T22:30:00Z");

  static final String CUSTOMER = Giro010Checkout.EMAIL;
  static final String SUBJECT = "Conferma ordine 2026-0001 — Fioreria Il Glicine";
  static final String FROM_NAME = "Fioreria Il Glicine tramite Gerio";
  static final String SHOP_EMAIL = "ciao@glicine.example";
  static final String DEMO_VAT = "IT00000000000";

  @Inject DataSource dataSource;

  private Giro015Data data;
  private Giro010FakeStripe stripe;
  private final TestClock clock = new TestClock(LATE_EVENING_UTC);

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

  private Placed order() throws Exception {
    return placeDemoOrder(data.giro014.giro011.checkout, stripe);
  }

  private static String paid(String eventId, Placed order) {
    return event(eventId, COMPLETED, DEMO_ACCOUNT, paidSession(order, order.sessionId(), "pi_g15"));
  }

  private static Sent onlyMailTo(String address) {
    List<Sent> mails = Giro015Mail.sentTo(address);
    assertEquals(1, mails.size(), "mails to " + address + ": " + mails);
    return mails.get(0);
  }

  /** NBSP or a plain space before the euro sign, as on the status page. */
  private static boolean hasAmount(String text, String amount) {
    return text.contains(amount + " €") || text.contains(amount + " €");
  }

  @Test
  void aPaidOrderQueuesOneRowWithoutPersonalDataAndOnePassSendsTheConfirmation() throws Exception {
    Placed order = order();
    sendOk(paid("evt_giro015_paid_1", order), "paid");
    assertEquals("PAID", data.giro014.giro011.status(order.orderId()), "order status");

    Map<String, Object> row = data.onlyRow(order.orderId());
    assertEquals(Giro005DemoData.DEMO_ID, row.get("tenant_id"), "tenant_id of the row");
    assertEquals("EMAIL", row.get("channel"), "channel");
    assertEquals("ORDER_CONFIRMATION", row.get("kind"), "payload kind");
    assertEquals("PENDING", row.get("status"), "status before the worker");
    String payload = (String) row.get("payload_text");
    assertFalse(payload.contains("@"), "payload holds an email address: " + payload);
    assertFalse(payload.contains("Giulia"), "payload holds the customer's name: " + payload);
    assertEquals(0, Giro015Mail.total(), "mail sent before the worker ran");

    Giro015Mail.drainOnce();

    Sent mail = onlyMailTo(CUSTOMER);
    assertEquals(1, Giro015Mail.total(), "mails in total");
    assertEquals(List.of(CUSTOMER), mail.to(), "To");
    assertEquals(SUBJECT, mail.subject(), "Subject");
    assertNotNull(mail.from(), "From");
    assertTrue(mail.from().contains(FROM_NAME), "From without «" + FROM_NAME + "»: " + mail.from());
    assertTrue(mail.from().matches(".*<ordini@[^>]+>.*"), "From not <ordini@…>: " + mail.from());
    assertNotNull(mail.replyTo(), "Reply-To");
    assertTrue(mail.replyTo().contains(SHOP_EMAIL), "Reply-To: " + mail.replyTo());
    assertNotNull(mail.html(), "no HTML part");
    assertNotNull(mail.text(), "no text part");
    assertEquals("SENT", data.onlyRow(order.orderId()).get("status"), "status after the worker");

    // A second pass sends nothing more.
    Giro015Mail.drainOnce();
    assertEquals(1, Giro015Mail.total(), "the worker sent the confirmation twice");
  }

  @Test
  void bothPartsCarryTheOrderTheSellerTheWithdrawalNoteAndAWorkingLink() throws Exception {
    Placed order = order();
    sendOk(paid("evt_giro015_paid_1", order), "paid");
    Giro015Mail.drainOnce();
    Sent mail = onlyMailTo(CUSTOMER);

    for (String part : List.of("html", "text")) {
      String text = "html".equals(part) ? Giro015Mail.text(mail.html()) : mail.text();
      String what = part + " part: ";
      assertTrue(text.contains("2026-0001"), what + "no order number 2026-0001");
      assertTrue(text.contains("12/10/2026"), what + "no order date 12/10/2026 (Europe/Rome)");
      assertFalse(text.contains("11/10/2026"), what + "order date in UTC");
      assertTrue(text.contains("Mazzo di rose rosse"), what + "no roses line");
      assertTrue(text.contains("12 rose"), what + "no variant «12 rose»");
      assertTrue(text.contains("Orchidea Phalaenopsis bianca"), what + "no orchid line");
      assertTrue(hasAmount(text, "45,00"), what + "no 45,00 €");
      assertTrue(hasAmount(text, "59,80"), what + "no orchid line total 59,80 €");
      assertTrue(hasAmount(text, "104,80"), what + "no total 104,80 €");
      assertTrue(text.contains("Carta"), what + "no payment «Carta»");
      assertTrue(text.contains("Ritiro in negozio"), what + "no «Ritiro in negozio»");
      assertTrue(text.contains("13/10/2026"), what + "no pickup date 13/10/2026");
      assertTrue(text.contains("Il Glicine di Lucia Ferri"), what + "no legal name");
      assertTrue(text.contains("Via dei Fossi 12"), what + "no address");
      assertTrue(text.contains("55100 Lucca"), what + "no postal code and city");
      assertTrue(text.contains(DEMO_VAT), what + "no VAT number " + DEMO_VAT);
      assertTrue(text.contains(SHOP_EMAIL), what + "no shop email");
      assertTrue(text.contains("+39 0583 000000"), what + "no shop phone");
      assertTrue(text.toLowerCase().contains("recesso"), what + "no «recesso»");
      assertTrue(text.contains("art. 59"), what + "no «art. 59»");
      assertTrue(text.toLowerCase().contains("garanzia legale"), what + "no «garanzia legale»");
    }

    String token = data.giro014.token(order.orderId());
    String expected = DEMO + "/" + token;
    assertEquals(
        List.of(expected),
        Giro015Mail.statusLinks(mail.html()).stream().distinct().toList(),
        "HTML: status links");
    assertEquals(
        List.of(expected),
        Giro015Mail.statusLinks(mail.text()).stream().distinct().toList(),
        "text: status links");

    // The link taken from the mail works on the shop's host.
    Response page =
        given().redirects().follow(false).header("Host", DEMO).when().get("/ordine/" + token);
    assertEquals(200, page.statusCode(), "status page from the mail link");
    assertTrue(Giro014Data.body(page).contains("2026-0001"), "status page of another order");
  }

  @Test
  void theSameEventTwiceOrTwoEventsOfTheSameOrderGiveOneRowAndOneMail() throws Exception {
    Placed order = order();
    sendOk(paid("evt_giro015_paid_1", order), "paid");
    sendOk(paid("evt_giro015_paid_1", order), "same event again");
    sendOk(paid("evt_giro015_paid_2", order), "another event for the same session");
    assertEquals(1, data.outbox(order.orderId()).size(), "outbox rows of the order");

    Giro015Mail.drainOnce();
    Giro015Mail.drainOnce();
    assertEquals(1, Giro015Mail.sentTo(CUSTOMER).size(), "mails to the customer");
  }

  @Test
  void twoPaidEventsInParallelGiveOneRowAndOneMail() throws Exception {
    Placed order = order();
    List<String> events =
        List.of(paid("evt_giro015_par_1", order), paid("evt_giro015_par_2", order));
    parallel(
        events.stream()
            .<Callable<Void>>map(
                e ->
                    () -> {
                      sendOk(e, "parallel paid event");
                      return null;
                    })
            .toList());

    assertEquals("PAID", data.giro014.giro011.status(order.orderId()), "order status");
    assertEquals(1, data.outbox(order.orderId()).size(), "outbox rows after two parallel events");
    Giro015Mail.drainOnce();
    assertEquals(1, Giro015Mail.total(), "mails after two parallel events");
  }

  @Test
  void twoWorkerPassesInParallelSendOneMail() throws Exception {
    Placed order = order();
    sendOk(paid("evt_giro015_paid_1", order), "paid");

    Callable<Void> pass =
        () -> {
          Giro015Mail.drainOnce();
          return null;
        };
    parallel(List.of(pass, pass));

    assertEquals(1, Giro015Mail.total(), "mails after two parallel worker passes");
    assertEquals("SENT", data.onlyRow(order.orderId()).get("status"), "row status");
  }

  @Test
  void pendingUnpaidAndExpiredOrdersQueueNothingAndGetNoMail() throws Exception {
    Placed pending = order();
    Giro015Mail.drainOnce();
    assertEquals(0, data.outbox().size(), "outbox rows of a pending order");

    // Session complete but not paid yet (e.g. a delayed method): still PENDING_PAYMENT.
    sendOk(
        event(
            "evt_giro015_unpaid_1",
            COMPLETED,
            DEMO_ACCOUNT,
            Giro011Webhooks.session(
                pending.sessionId(),
                pending.orderId(),
                Giro005DemoData.DEMO_ID,
                pending.number(),
                "complete",
                "unpaid",
                null)),
        "complete but unpaid");
    assertEquals("PENDING_PAYMENT", data.giro014.giro011.status(pending.orderId()), "unpaid");
    assertEquals(0, data.outbox().size(), "outbox rows of an unpaid session");

    sendOk(
        event(
            "evt_giro015_expired_1",
            EXPIRED,
            DEMO_ACCOUNT,
            expiredSession(pending, pending.sessionId())),
        "expired");
    assertEquals("EXPIRED", data.giro014.giro011.status(pending.orderId()), "expired");
    assertEquals(0, data.outbox().size(), "outbox rows of an expired order");

    Giro015Mail.drainOnce();
    assertEquals(0, Giro015Mail.total(), "mails for pending, unpaid and expired orders");
  }

  /** Runs the tasks together (started by one latch) and fails on the first failure. */
  static void parallel(List<Callable<Void>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Void>> futures =
          tasks.stream()
              .map(
                  task ->
                      pool.submit(
                          () -> {
                            start.await();
                            return task.call();
                          }))
              .toList();
      start.countDown();
      for (Future<Void> future : futures) {
        try {
          future.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
          if (e.getCause() instanceof AssertionError assertion) {
            throw assertion;
          }
          throw e;
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
