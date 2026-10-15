package it.gerio.platform.acceptance;

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
import static it.gerio.platform.acceptance.Giro016Terms.FILE_NAME;
import static it.gerio.platform.acceptance.Giro016Terms.VERSION_LINE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import it.gerio.platform.acceptance.Giro011Webhooks.Placed;
import it.gerio.platform.acceptance.Giro015Mail.Sent;
import it.gerio.platform.acceptance.Giro016Terms.Attached;
import it.gerio.platform.common.TestClock;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 016: the confirmation email carries the PDF of the terms in the version the order accepted
 * ({@code terms_version}), with the data of the order's own shop; an order with a version the
 * platform does not know gets no mail and its row is retried (docs/lab/giri/016-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro016TermsAttachmentAcceptanceTest {

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

  private Placed paidDemoOrder(String suffix) throws Exception {
    Placed order = placeDemoOrder(data.giro014.giro011.checkout, stripe);
    sendOk(
        event(
            "evt_giro016_" + suffix,
            COMPLETED,
            DEMO_ACCOUNT,
            paidSession(order, order.sessionId(), "pi_g16_" + suffix)),
        "paid " + suffix);
    return order;
  }

  /** The only attachment, checked for name, type and magic number. */
  private static byte[] onlyTermsPdf(List<Attached> attachments, String what) {
    assertEquals(1, attachments.size(), what + ": attachments " + attachments);
    Attached pdf = attachments.get(0);
    assertEquals(FILE_NAME, pdf.name(), what + ": file name");
    assertNotNull(pdf.contentType(), what + ": no content type");
    assertTrue(pdf.contentType().startsWith("application/pdf"), what + ": " + pdf.contentType());
    Giro016Terms.assertPdf(pdf.data(), what);
    return pdf.data();
  }

  @Test
  void theConfirmationCarriesTheTermsPdfOfTheShopInTheOrdersVersion() throws Exception {
    paidDemoOrder("one");
    Giro015Mail.drainOnce();

    List<List<Attached>> mails = Giro016Terms.attachmentsOfMailsTo(CUSTOMER);
    assertEquals(1, mails.size(), "mails to the customer");
    byte[] pdf = onlyTermsPdf(mails.get(0), "demo mail");

    String text = Giro016Terms.text(pdf);
    for (String title : Giro016Terms.TITLES) {
      assertTrue(text.contains(title), "attached PDF: no title «" + title + "»");
    }
    assertTrue(text.contains(DEMO_VAT), "attached PDF: no demo VAT number");
    assertTrue(text.contains(VERSION_LINE), "attached PDF: no «" + VERSION_LINE + "»");
    assertTrue(text.contains(Giro016Terms.ACCENTED), "attached PDF: no accented sentence");
    assertFalse(text.contains(Giro015Data.MARCO_VAT), "attached PDF shows Marco's VAT number");
  }

  @Test
  void bothPartsOfTheMailSayTheTermsAreAttached() throws Exception {
    paidDemoOrder("parts");
    Giro015Mail.drainOnce();
    List<Sent> mails = Giro015Mail.sentTo(CUSTOMER);
    assertEquals(1, mails.size(), "mails to the customer");
    Sent mail = mails.get(0);
    for (String part : List.of("html", "text")) {
      String text = "html".equals(part) ? Giro015Mail.text(mail.html()) : mail.text();
      assertNotNull(text, part + " part missing");
      assertTrue(
          text.toLowerCase().contains("condizioni di vendita"),
          part + " part: no «condizioni di vendita»");
      assertTrue(text.contains("in allegato"), part + " part: no «in allegato»");
    }
  }

  @Test
  void twoMailsOfTheSameShopCarryTheSamePdfAsTheShopsPage() throws Exception {
    paidDemoOrder("first");
    paidDemoOrder("second");
    Giro015Mail.drainOnce();

    List<List<Attached>> mails = Giro016Terms.attachmentsOfMailsTo(CUSTOMER);
    assertEquals(2, mails.size(), "mails to the customer");
    byte[] first = onlyTermsPdf(mails.get(0), "first mail");
    byte[] second = onlyTermsPdf(mails.get(1), "second mail");
    assertArrayEquals(first, second, "two mails of the same shop carry different PDFs");
    assertArrayEquals(first, Giro016Terms.pdf(DEMO), "the attachment differs from the page PDF");
  }

  @Test
  void eachShopsMailCarriesItsOwnTerms() throws Exception {
    Giro010CheckoutData checkout = data.giro014.giro011.checkout;
    paidDemoOrder("iso");
    Giro008Browser marcoBrowser = new Giro008Browser(MARCO);
    marcoBrowser.page("/prodotti/bouquet-di-stagione", 200);
    marcoBrowser.addOk(checkout.cart.marco, 1);
    Placed marco = Giro011Webhooks.checkout(marcoBrowser, checkout, stripe, MARCO_ID);
    sendOk(
        event(
            "evt_giro016_marco",
            COMPLETED,
            MARCO_ACCOUNT,
            Giro011Webhooks.session(
                marco.sessionId(),
                marco.orderId(),
                MARCO_ID,
                marco.number(),
                "complete",
                "paid",
                "pi_g16_marco")),
        "Marco's paid event");

    Giro015Mail.drainOnce();

    String demoText =
        Giro016Terms.text(
            onlyTermsPdf(
                Giro016Terms.attachmentsOfMailTo(CUSTOMER, "Fioreria Il Glicine"), "demo mail"));
    String marcoText =
        Giro016Terms.text(
            onlyTermsPdf(
                Giro016Terms.attachmentsOfMailTo(CUSTOMER, "Fiori di Marco"), "Marco's mail"));

    assertTrue(demoText.contains(DEMO_VAT), "demo attachment: no demo VAT number");
    assertFalse(demoText.contains(Giro015Data.MARCO_VAT), "demo attachment: Marco's VAT number");
    assertTrue(marcoText.contains("Fiori di Marco Srl"), "Marco's attachment: no legal name");
    assertTrue(marcoText.contains(Giro015Data.MARCO_VAT), "Marco's attachment: no VAT number");
    assertFalse(marcoText.contains(DEMO_VAT), "Marco's attachment: the demo's VAT number");
    assertFalse(marcoText.contains("Glicine"), "Marco's attachment: the demo's name");
    assertEquals(
        Giro016Terms.text(Giro016Terms.pdf(MARCO)), marcoText, "Marco's attachment ≠ Marco's page");
    assertEquals(
        List.of("SENT", "SENT"), data.outbox().stream().map(r -> r.get("status")).toList());
  }

  @Test
  void anOrderWithAnUnknownTermsVersionGetsNoMailAndItsRowIsRetried() throws Exception {
    Placed order = paidDemoOrder("unknown");
    setTermsVersion(order.orderId(), "2019-01-01");

    Giro015Mail.drainOnce();

    assertEquals(0, Giro015Mail.total(), "a mail left with the wrong terms");
    Map<String, Object> row = data.onlyRow(order.orderId());
    assertEquals("PENDING", row.get("status"), "row of an order with an unknown version");
    assertEquals(1, ((Number) row.get("attempts")).intValue(), "attempts");
    assertNotNull(row.get("last_error"), "last_error");
  }

  private void setTermsVersion(long orderId, String version) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update customer_order set terms_version = ? where id = ?")) {
      statement.setString(1, version);
      statement.setLong(2, orderId);
      assertEquals(1, statement.executeUpdate(), "order " + orderId + " not updated");
    }
  }
}
