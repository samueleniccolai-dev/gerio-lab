package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro008CartData.MARCO;
import static it.gerio.platform.acceptance.Giro008CartData.PLATFORM;
import static it.gerio.platform.acceptance.Giro015ConfirmationEmailAcceptanceTest.DEMO_VAT;
import static it.gerio.platform.acceptance.Giro016Terms.ACCENTED;
import static it.gerio.platform.acceptance.Giro016Terms.FILE_NAME;
import static it.gerio.platform.acceptance.Giro016Terms.PAGE;
import static it.gerio.platform.acceptance.Giro016Terms.PDF;
import static it.gerio.platform.acceptance.Giro016Terms.TITLES;
import static it.gerio.platform.acceptance.Giro016Terms.UNKNOWN_HOST;
import static it.gerio.platform.acceptance.Giro016Terms.VERSION_LINE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 016: each shop has its terms of sale at {@code /condizioni-di-vendita}, as a page and as a
 * PDF, with its own legal data only; no shop, no terms (docs/lab/giri/016-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro016TermsAcceptanceTest {

  static final String DEMO_LEGAL_NAME = "Il Glicine di Lucia Ferri";
  static final String MARCO_LEGAL_NAME = "Fiori di Marco Srl";

  @Inject DataSource dataSource;

  private Giro015Data data;

  @BeforeEach
  void setUp() throws Exception {
    data = new Giro015Data(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  private static String page(String host) {
    Response response = Giro016Terms.get(host, PAGE);
    assertEquals(200, response.statusCode(), "GET " + PAGE + " on " + host);
    String type = response.getContentType();
    assertTrue(type != null && type.startsWith("text/html"), "Content-Type: " + type);
    return Giro015Mail.text(response.asString());
  }

  private static void assertTerms(String text, String where) {
    for (String title : TITLES) {
      assertTrue(text.contains(title), where + ": no title «" + title + "»");
    }
    assertTrue(text.contains(VERSION_LINE), where + ": no «" + VERSION_LINE + "»");
  }

  @Test
  void theDemoPageShowsTheFiveSectionsTheDemoSellerAndTheVersion() {
    String text = page(DEMO);
    assertTerms(text, "demo page");
    assertTrue(text.contains(DEMO_LEGAL_NAME), "demo page: no legal name");
    assertTrue(text.contains(DEMO_VAT), "demo page: no VAT number");
    assertTrue(text.contains(ACCENTED), "demo page: no «" + ACCENTED + "»");
    assertFalse(text.contains(Giro015Data.MARCO_VAT), "demo page shows Marco's VAT number");
  }

  @Test
  void marcosPageShowsMarcosSellerAndNothingOfTheDemo() {
    String text = page(MARCO);
    assertTerms(text, "Marco's page");
    assertTrue(text.contains(MARCO_LEGAL_NAME), "Marco's page: no legal name");
    assertTrue(text.contains(Giro015Data.MARCO_VAT), "Marco's page: no VAT number");
    assertTrue(text.contains(Giro015Data.MARCO_ADDRESS), "Marco's page: no address");
    for (String foreign :
        List.of("Glicine", DEMO_VAT, "Via dei Fossi 12", "ciao@glicine.example")) {
      assertFalse(text.contains(foreign), "Marco's page shows the demo's «" + foreign + "»");
    }
  }

  @Test
  void noShopNoTerms() {
    for (String host : List.of(PLATFORM, UNKNOWN_HOST)) {
      for (String path : List.of(PAGE, PDF)) {
        assertEquals(404, Giro016Terms.get(host, path).statusCode(), "GET " + path + " on " + host);
      }
    }
  }

  @Test
  void theDemoPdfIsInlineNamedAfterTheVersionAndCarriesTheSameText() {
    Response response = Giro016Terms.get(DEMO, PDF);
    assertEquals(200, response.statusCode(), "GET " + PDF);
    assertEquals(
        "inline; filename=\"" + FILE_NAME + "\"",
        response.getHeader("Content-Disposition"),
        "Content-Disposition");
    byte[] pdf = Giro016Terms.pdf(DEMO);
    String text = Giro016Terms.text(pdf);
    assertTerms(text, "demo PDF");
    assertTrue(text.contains(DEMO_LEGAL_NAME), "demo PDF: no legal name");
    assertTrue(text.contains(DEMO_VAT), "demo PDF: no VAT number");
    assertTrue(text.contains(ACCENTED), "demo PDF: no «" + ACCENTED + "» (accents)");
    assertFalse(text.contains(Giro015Data.MARCO_VAT), "demo PDF shows Marco's VAT number");
  }

  @Test
  void marcosPdfCarriesMarcosSellerOnly() {
    String text = Giro016Terms.text(Giro016Terms.pdf(MARCO));
    assertTerms(text, "Marco's PDF");
    assertTrue(text.contains(MARCO_LEGAL_NAME), "Marco's PDF: no legal name");
    assertTrue(text.contains(Giro015Data.MARCO_VAT), "Marco's PDF: no VAT number");
    assertFalse(text.contains(DEMO_VAT), "Marco's PDF shows the demo's VAT number");
    assertFalse(text.contains("Glicine"), "Marco's PDF shows the demo's name");
  }

  @Test
  void twoRequestsAtOnceGetTheSamePdfAndSoDoesALaterOne() throws Exception {
    for (String host : List.of(DEMO, MARCO)) {
      CountDownLatch start = new CountDownLatch(1);
      Callable<byte[]> request =
          () -> {
            start.await(10, TimeUnit.SECONDS);
            return Giro016Terms.pdf(host);
          };
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<byte[]> first = pool.submit(request);
        Future<byte[]> second = pool.submit(request);
        start.countDown();
        byte[] a = result(first);
        byte[] b = result(second);
        assertArrayEquals(a, b, "two requests at once on " + host + " got different PDFs");
        assertArrayEquals(a, Giro016Terms.pdf(host), "a later request on " + host + " differs");
      } finally {
        pool.shutdownNow();
      }
    }
  }

  /** The value of the request; an assertion that failed in the thread fails the test as such. */
  private static byte[] result(Future<byte[]> request) throws Exception {
    try {
      return request.get(60, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof AssertionError assertion) {
        throw assertion;
      }
      throw e;
    }
  }
}
