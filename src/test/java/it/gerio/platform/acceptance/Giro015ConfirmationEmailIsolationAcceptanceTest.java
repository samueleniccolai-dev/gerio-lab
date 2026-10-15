package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro005DemoData.MARCO_ID;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro008CartData.MARCO;
import static it.gerio.platform.acceptance.Giro010CheckoutData.DEMO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro010CheckoutData.MARCO_ACCOUNT;
import static it.gerio.platform.acceptance.Giro011Webhooks.COMPLETED;
import static it.gerio.platform.acceptance.Giro011Webhooks.event;
import static it.gerio.platform.acceptance.Giro011Webhooks.paidSession;
import static it.gerio.platform.acceptance.Giro011Webhooks.placeDemoOrder;
import static it.gerio.platform.acceptance.Giro011Webhooks.sendOk;
import static it.gerio.platform.acceptance.Giro015ConfirmationEmailAcceptanceTest.CUSTOMER;
import static it.gerio.platform.acceptance.Giro015ConfirmationEmailAcceptanceTest.DEMO_VAT;
import static it.gerio.platform.acceptance.Giro015ConfirmationEmailAcceptanceTest.LATE_EVENING_UTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import it.gerio.platform.acceptance.Giro011Webhooks.Placed;
import it.gerio.platform.acceptance.Giro015Mail.Sent;
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
 * Giro 015: one worker pass over rows of two shops sends each mail with the data of its own shop
 * only, and each link works only on its own shop's host (docs/lab/giri/015-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro015ConfirmationEmailIsolationAcceptanceTest {

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

  @Test
  void oneWorkerPassSendsEachShopsMailWithItsOwnDataOnly() throws Exception {
    Giro010CheckoutData checkout = data.giro014.giro011.checkout;
    Placed demo = placeDemoOrder(checkout, stripe);
    Giro008Browser marcoBrowser = new Giro008Browser(MARCO);
    marcoBrowser.page("/prodotti/bouquet-di-stagione", 200);
    marcoBrowser.addOk(checkout.cart.marco, 1);
    Placed marco = Giro011Webhooks.checkout(marcoBrowser, checkout, stripe, MARCO_ID);

    sendOk(
        event(
            "evt_giro015_marco_1",
            COMPLETED,
            MARCO_ACCOUNT,
            Giro011Webhooks.session(
                marco.sessionId(),
                marco.orderId(),
                MARCO_ID,
                marco.number(),
                "complete",
                "paid",
                "pi_g15_marco")),
        "Marco's paid event");
    sendOk(
        event(
            "evt_giro015_demo_1",
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(demo, demo.sessionId(), "pi_g15_demo")),
        "demo paid event");

    assertEquals(DEMO_ID, data.onlyRow(demo.orderId()).get("tenant_id"), "demo row tenant");
    assertEquals(MARCO_ID, data.onlyRow(marco.orderId()).get("tenant_id"), "Marco row tenant");

    // One pass over both rows.
    Giro015Mail.drainOnce();

    List<Sent> mails = Giro015Mail.sentTo(CUSTOMER);
    assertEquals(2, mails.size(), "mails to the customer (one per shop): " + mails);
    Sent demoMail =
        mails.stream()
            .filter(m -> String.valueOf(m.subject()).contains("Fioreria Il Glicine"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no mail of the demo shop: " + mails));
    Sent marcoMail =
        mails.stream()
            .filter(m -> String.valueOf(m.subject()).contains("Fiori di Marco"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no mail of Marco's shop: " + mails));

    String demoText = demoMail.bodies();
    assertTrue(demoText.contains("Il Glicine di Lucia Ferri"), "demo mail: no own legal name");
    assertTrue(demoText.contains(DEMO_VAT), "demo mail: no own VAT number");
    for (String foreign :
        List.of(
            "Fiori di Marco",
            Giro015Data.MARCO_VAT,
            Giro015Data.MARCO_ADDRESS,
            "marco@example.test")) {
      assertFalse(demoText.contains(foreign), "demo mail shows Marco's «" + foreign + "»");
    }
    assertTrue(
        String.valueOf(demoMail.replyTo()).contains("ciao@glicine.example"), "demo Reply-To");

    String marcoText = marcoMail.bodies();
    assertTrue(marcoText.contains("Fiori di Marco Srl"), "Marco mail: no own legal name");
    assertTrue(marcoText.contains(Giro015Data.MARCO_VAT), "Marco mail: no own VAT number");
    for (String foreign :
        List.of(
            "Glicine",
            DEMO_VAT,
            "Via dei Fossi 12",
            "ciao@glicine.example",
            "+39 0583 000000",
            "Mazzo di rose rosse")) {
      assertFalse(marcoText.contains(foreign), "Marco mail shows the demo's «" + foreign + "»");
    }
    assertTrue(
        String.valueOf(marcoMail.replyTo()).contains("marco@example.test"), "Marco Reply-To");

    // Each link points at its own shop and works only there.
    String demoToken = data.giro014.token(demo.orderId());
    String marcoToken = data.giro014.token(marco.orderId());
    assertEquals(
        List.of(DEMO + "/" + demoToken),
        Giro015Mail.statusLinks(demoMail.text()).stream().distinct().toList(),
        "demo mail links");
    assertEquals(
        List.of(MARCO + "/" + marcoToken),
        Giro015Mail.statusLinks(marcoMail.text()).stream().distinct().toList(),
        "Marco mail links");
    assertEquals(200, status(DEMO, demoToken), "demo link on the demo host");
    assertEquals(200, status(MARCO, marcoToken), "Marco link on Marco's host");
    assertEquals(404, status(MARCO, demoToken), "demo token on Marco's host");
    assertEquals(404, status(DEMO, marcoToken), "Marco token on the demo host");

    assertEquals(
        List.of("SENT", "SENT"), data.outbox().stream().map(r -> r.get("status")).toList());
  }

  private static int status(String host, String token) {
    return given()
        .redirects()
        .follow(false)
        .header("Host", host)
        .when()
        .get("/ordine/" + token)
        .statusCode();
  }
}
