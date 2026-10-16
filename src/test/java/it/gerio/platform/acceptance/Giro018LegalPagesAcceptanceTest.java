package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro018Legal.ACCESSIBILITY;
import static it.gerio.platform.acceptance.Giro018Legal.COOKIES;
import static it.gerio.platform.acceptance.Giro018Legal.DEMO_CITY;
import static it.gerio.platform.acceptance.Giro018Legal.DEMO_EMAIL;
import static it.gerio.platform.acceptance.Giro018Legal.DEMO_LEGAL_NAME;
import static it.gerio.platform.acceptance.Giro018Legal.DEMO_PEC;
import static it.gerio.platform.acceptance.Giro018Legal.DEMO_PHONE;
import static it.gerio.platform.acceptance.Giro018Legal.DEMO_REA_LINE;
import static it.gerio.platform.acceptance.Giro018Legal.DEMO_STREET;
import static it.gerio.platform.acceptance.Giro018Legal.DEMO_VAT;
import static it.gerio.platform.acceptance.Giro018Legal.PRIVACY;
import static it.gerio.platform.acceptance.Giro018Legal.SELLER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 018 (goal T8): the four legal pages of the demo shop, their content, the data read from the
 * shop and the seed that brings PEC and REA back (docs/lab/giri/018-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro018LegalPagesAcceptanceTest {

  private static final Pattern DATE = Pattern.compile("\\b\\d{2}/\\d{2}/\\d{4}\\b");

  @Inject DataSource dataSource;

  private Giro014Data data;

  @BeforeEach
  void setUp() throws Exception {
    data = new Giro014Data(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  private static String text(String path) {
    return Giro018Legal.text(Giro018Legal.page(DEMO, path));
  }

  private static void assertContains(String text, String where, List<String> expected) {
    for (String e : expected) {
      assertTrue(text.contains(e), where + ": no «" + e + "»");
    }
  }

  @Test
  void theSellerPageShowsTheLegalDataOfTheDemo() {
    String text = text(SELLER);
    assertTrue(text.contains("Informazioni sul venditore"), "no title");
    assertContains(
        text,
        SELLER,
        List.of(
            DEMO_LEGAL_NAME,
            DEMO_STREET,
            DEMO_CITY,
            DEMO_EMAIL,
            DEMO_PEC,
            DEMO_VAT,
            DEMO_REA_LINE,
            DEMO_PHONE));
  }

  @Test
  void thePrivacyPageHasTheItemsOfArticle13() {
    String text = text(PRIVACY);
    assertContains(
        text,
        PRIVACY,
        List.of(
            "Informativa sulla privacy",
            DEMO_LEGAL_NAME,
            DEMO_EMAIL,
            "destinatario",
            "contratto",
            "obbligo di legge",
            "art. 6",
            "10 anni",
            "12 mesi",
            "Stripe",
            "email",
            "accesso",
            "rettifica",
            "cancellazione",
            "opposizione",
            "Garante per la protezione dei dati personali"));
  }

  @Test
  void theCookiePageSaysTechnicalCookiesOnly() {
    String text = text(COOKIES);
    assertContains(
        text,
        COOKIES,
        List.of(
            "Informativa sui cookie",
            "cookie tecnici",
            Giro008Browser.CART_COOKIE,
            Giro008Browser.CSRF,
            "profilazione",
            "terze parti"));
  }

  @Test
  void theAccessibilityPageHasStandardStatusContactAndDate() {
    String text = text(ACCESSIBILITY);
    assertContains(
        text,
        ACCESSIBILITY,
        List.of(
            "Dichiarazione di accessibilità", "WCAG 2.1", "AA", "Stato di conformità", DEMO_EMAIL));
    assertTrue(DATE.matcher(text).find(), ACCESSIBILITY + ": no date dd/mm/yyyy");
  }

  @Test
  void theSellerPageFollowsTheShopDataNotTheTemplate() throws SQLException {
    Giro018Legal.setTenantColumn(dataSource, DEMO_ID, "pec", "altra@pec.example");
    String text = text(SELLER);
    assertTrue(text.contains("altra@pec.example"), "the new PEC is not shown");
    assertFalse(text.contains(DEMO_PEC), "the old PEC is still shown");
  }

  @Test
  void runningTheSeedAgainBringsBackPecAndRea() throws Exception {
    Giro018Legal.setTenantColumn(dataSource, DEMO_ID, "pec", null);
    Giro018Legal.setTenantColumn(dataSource, DEMO_ID, "rea", null);

    new Giro005DemoData(dataSource).runSeed();

    assertEquals(DEMO_PEC, Giro018Legal.tenantColumn(dataSource, DEMO_ID, "pec"), "tenant.pec");
    assertEquals("LU-000000", Giro018Legal.tenantColumn(dataSource, DEMO_ID, "rea"), "tenant.rea");
    String text = text(SELLER);
    assertTrue(text.contains(DEMO_PEC), "after the seed: no PEC on the seller page");
    assertTrue(text.contains(DEMO_REA_LINE), "after the seed: no REA on the seller page");
  }
}
