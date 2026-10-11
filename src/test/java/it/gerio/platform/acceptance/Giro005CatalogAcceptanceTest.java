package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import it.gerio.platform.tenant.TestShops;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 005 (goal T3, part 1): catalog, category page and product page of the demo shop, served on
 * the shop host. Black box over HTTP; the data is the production seed (run twice, it must be
 * idempotent) plus a second shop that reuses a product slug. Prices use U+00A0 before the euro
 * sign.
 */
@QuarkusTest
class Giro005CatalogAcceptanceTest {

  private static final String DEMO = "demo.localhost";
  private static final String MARCO = "fiori-di-marco.localhost";
  private static final String PLATFORM = "localhost";
  private static final String NBSP = "\u00a0";

  private static final String SEASONAL =
      "Composizione realizzata con fiori di stagione: può differire dalla foto mantenendo stile e"
          + " valore.";
  private static final String WITHDRAWAL =
      "Fiori e piante sono deperibili: il diritto di recesso è escluso.";

  @Inject DataSource dataSource;

  private TestShops shops;
  private Giro005DemoData data;

  @BeforeEach
  void setUp() throws IOException, SQLException {
    shops = new TestShops(dataSource);
    data = new Giro005DemoData(dataSource);
    data.runSeed();
    data.runSeed();
    data.insertMarco();
  }

  @AfterEach
  void tearDown() throws SQLException {
    shops.deleteAll();
  }

  @Test
  void theSeedRunTwiceLeavesEachRowOnce() throws SQLException {
    assertEquals(4, data.count("category", DEMO_ID), "demo categories");
    assertEquals(7, data.count("product", DEMO_ID), "demo products");
    assertEquals(12, data.count("product_variant", DEMO_ID), "demo variants");
  }

  @Test
  void theCatalogShowsVisibleCategoriesInOrderWithFromPrices() {
    String html = page(DEMO, "/catalogo", 200);
    assertH1(html, "Catalogo");
    int bouquet = indexOf(html, "href=\"/catalogo/bouquet\"");
    int piante = indexOf(html, "href=\"/catalogo/piante\"");
    int composizioni = indexOf(html, "href=\"/catalogo/composizioni\"");
    assertTrue(bouquet < piante && piante < composizioni, "categories out of order");
    assertEquals(1, occurrences(html, "href=\"/prodotti/rose-rosse\""), "links to rose-rosse");
    assertContains(html, "Orchidea Phalaenopsis bianca");
    assertContains(html, "href=\"/prodotti/bouquet-di-stagione\"");
    assertContains(html, "href=\"/prodotti/composizione-in-cesto\"");
    assertContains(html, "da 25,00" + NBSP + "€");
    assertContains(html, "da 29,90" + NBSP + "€");
    assertContains(html, "da 45,00" + NBSP + "€");
    assertContains(html, "da 55,00" + NBSP + "€");
    assertAbsent(
        html,
        "/catalogo/natale",
        "stella-di-natale",
        "Stella di Natale",
        "corona-avvento",
        "Avvento",
        "bouquet-sposa",
        "Bouquet da sposa",
        "Bouquet di Marco",
        "30,00");
  }

  @Test
  void theCategoryPageShowsOnlyItsVisibleProducts() {
    String html = page(DEMO, "/catalogo/bouquet", 200);
    assertH1(html, "Bouquet");
    assertContains(html, "Bouquet di stagione");
    assertContains(html, "Mazzo di rose rosse");
    assertContains(html, "href=\"/prodotti/rose-rosse\"");
    assertAbsent(html, "Orchidea", "bouquet-sposa", "Bouquet da sposa");
  }

  @Test
  void hiddenMissingAndForeignCategoriesAreNotFound() {
    page(DEMO, "/catalogo/natale", 404);
    page(DEMO, "/catalogo/inesistente", 404);
    page(DEMO, "/catalogo/fiori", 404);
  }

  @Test
  void theProductPageShowsActiveVariantsPricesAndNotices() {
    String html = page(DEMO, "/prodotti/bouquet-di-stagione", 200);
    assertH1(html, "Bouquet di stagione");
    assertContains(html, "Fiori freschi scelti ogni mattina al mercato, legati a mano.");
    int small = indexOf(html, "Piccolo");
    int medium = indexOf(html, "Medio");
    int large = indexOf(html, "Grande");
    assertTrue(small < medium && medium < large, "variants out of order");
    assertContains(html, "25,00" + NBSP + "€");
    assertContains(html, "35,00" + NBSP + "€");
    assertContains(html, "49,00" + NBSP + "€");
    assertContains(html, "IVA inclusa");
    assertContains(html, SEASONAL);
    assertContains(html, WITHDRAWAL);
    assertAbsent(html, "69,00", "Extra", "Unico", "30,00", "Tempo di preparazione");
  }

  @Test
  void aPlantHasTheWithdrawalNoticeButNoSeasonalNote() {
    String html = page(DEMO, "/prodotti/orchidea-bianca", 200);
    assertH1(html, "Orchidea Phalaenopsis bianca");
    assertContains(html, "29,90" + NBSP + "€");
    assertContains(html, WITHDRAWAL);
    assertAbsent(html, "fiori di stagione");
  }

  @Test
  void aProductWithLeadTimeShowsIt() {
    String html = page(DEMO, "/prodotti/composizione-in-cesto", 200);
    assertH1(html, "Composizione in cesto");
    assertContains(html, "Tempo di preparazione: 1 giorno");
    assertContains(html, SEASONAL);
  }

  @Test
  void inactiveHiddenWithoutVariantsAndMissingProductsAreNotFound() {
    page(DEMO, "/prodotti/corona-avvento", 404);
    page(DEMO, "/prodotti/stella-di-natale", 404);
    page(DEMO, "/prodotti/bouquet-sposa", 404);
    page(DEMO, "/prodotti/inesistente", 404);
  }

  @Test
  void anotherShopWithTheSameSlugSeesOnlyItsOwnProduct() {
    String catalog = page(MARCO, "/catalogo", 200);
    assertContains(catalog, "href=\"/prodotti/bouquet-di-stagione\"");
    assertContains(catalog, "da 30,00" + NBSP + "€");
    assertAbsent(catalog, "rose-rosse", "Mazzo di rose rosse", "Orchidea", "25,00");

    String product = page(MARCO, "/prodotti/bouquet-di-stagione", 200);
    assertH1(product, "Bouquet di stagione");
    assertContains(product, "Unico");
    assertContains(product, "30,00" + NBSP + "€");
    assertAbsent(product, "Piccolo", "25,00", "Fiori freschi scelti");

    page(MARCO, "/prodotti/rose-rosse", 404);
    page(MARCO, "/catalogo/bouquet", 404);
  }

  @Test
  void thePlatformDomainHasNoCatalog() {
    page(PLATFORM, "/catalogo", 404);
    page(PLATFORM, "/prodotti/bouquet-di-stagione", 404);
  }

  @Test
  void theDemoHomeLinksToTheCatalog() {
    String html = page(DEMO, "/", 200);
    assertContains(html, "href=\"/catalogo\"");
  }

  private static String page(String host, String path, int status) {
    Response response = given().redirects().follow(false).header("Host", host).when().get(path);
    String body = new String(response.asByteArray(), StandardCharsets.UTF_8);
    assertEquals(status, response.statusCode(), "status of " + host + path);
    return body;
  }

  private static void assertH1(String html, String text) {
    Pattern h1 = Pattern.compile("<h1[^>]*>\\s*" + Pattern.quote(text) + "\\s*</h1>");
    assertTrue(h1.matcher(html).find(), "no <h1> with \"" + text + "\"");
  }

  private static void assertContains(String html, String text) {
    assertTrue(html.contains(text), "missing \"" + text + "\"");
  }

  private static void assertAbsent(String html, String... texts) {
    for (String text : texts) {
      assertFalse(html.contains(text), "unexpected \"" + text + "\"");
    }
  }

  private static int indexOf(String html, String text) {
    int index = html.indexOf(text);
    assertTrue(index >= 0, "missing \"" + text + "\"");
    return index;
  }

  private static int occurrences(String html, String text) {
    int count = 0;
    for (int i = html.indexOf(text); i >= 0; i = html.indexOf(text, i + 1)) {
      count++;
    }
    return count;
  }
}
