package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro008Browser.CART_COOKIE;
import static it.gerio.platform.acceptance.Giro008Browser.CSRF;
import static it.gerio.platform.acceptance.Giro008Browser.NBSP;
import static it.gerio.platform.acceptance.Giro008Browser.assertSeeOther;
import static it.gerio.platform.acceptance.Giro008Browser.body;
import static it.gerio.platform.acceptance.Giro008Browser.cookieValue;
import static it.gerio.platform.acceptance.Giro008Browser.hasAttribute;
import static it.gerio.platform.acceptance.Giro008Browser.setCookies;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 008 (goal T4): the cart of the demo shop with classic forms (no JavaScript). Black box over
 * HTTP on demo.localhost; every POST carries the CSRF token taken from a page, like a browser.
 * Data: production seed plus "Fiori di Marco" (docs/lab/giri/008-piano.md).
 */
@QuarkusTest
class Giro008CartAcceptanceTest {

  private static final String EMPTY = "Il tuo carrello è vuoto.";
  private static final String BOUQUET = "bouquet-di-stagione";
  private static final String ORCHID = "orchidea-bianca";

  @Inject DataSource dataSource;

  private Giro008CartData data;

  @BeforeEach
  void setUp() throws IOException, SQLException {
    data = new Giro008CartData(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  @Test
  void anEmptyCartSaysSoAndLinksToTheCatalog() {
    Giro008Browser browser = new Giro008Browser(DEMO);
    Response response = browser.get("/carrello");
    assertEquals(200, response.statusCode(), "status of GET /carrello");
    String html = body(response);
    assertTrue(
        Pattern.compile("<h1[^>]*>\\s*Carrello\\s*</h1>").matcher(html).find(), "no <h1>Carrello");
    assertTrue(html.contains(EMPTY), "missing \"" + EMPTY + "\"");
    assertTrue(html.contains("href=\"/catalogo\""), "no link to /catalogo");
    assertTrue(
        setCookies(response, CART_COOKIE).isEmpty(), "a GET must not create the cart cookie");
  }

  @Test
  void theProductPageHasOneAddFormPerActiveVariantWithTheCsrfToken() {
    Giro008Browser browser = new Giro008Browser(DEMO);
    Response response = browser.get("/prodotti/" + BOUQUET);
    assertEquals(200, response.statusCode(), "status of the product page");
    String html = body(response);
    assertTrue(html.contains("href=\"/carrello\""), "no link to /carrello in the page");

    List<String> csrfCookies = setCookies(response, CSRF);
    assertFalse(csrfCookies.isEmpty(), "the product page does not set the " + CSRF + " cookie");
    String cookie = cookieValue(csrfCookies.get(0));
    assertFalse(cookie.isEmpty(), "empty " + CSRF + " cookie");

    Matcher forms =
        Pattern.compile(
                "(<form[^>]*action=[\"']/carrello/aggiungi[\"'][^>]*>)(.*?)</form>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
            .matcher(html);
    Set<Long> variants = new TreeSet<>();
    int count = 0;
    while (forms.find()) {
      count++;
      assertTrue(
          forms.group(1).toLowerCase().contains("method=\"post\""),
          "form is not POST: " + forms.group(1));
      String inner = forms.group(2);
      variants.add(Long.parseLong(hiddenValue(inner, "variantId")));
      assertEquals(cookie, hiddenValue(inner, CSRF), "CSRF field differs from the cookie");
    }
    assertEquals(3, count, "add-to-cart forms on the product page");
    assertEquals(
        new TreeSet<>(List.of(data.piccolo, data.medio, data.grande)),
        variants,
        "variants of the add-to-cart forms (Extra is inactive)");
  }

  @Test
  void stepsOneToSixShowTheExpectedRowsAndTotals() {
    Giro008Browser browser = new Giro008Browser(DEMO);
    browser.page("/prodotti/" + BOUQUET, 200);

    // 1. Medio x 2
    browser.addOk(data.medio, 2);
    String html = browser.cartPage();
    assertEquals(1, Giro008Browser.rowCount(html), "rows after step 1");
    assertRow(html, BOUQUET, "Medio", "2", "35,00", "70,00");
    assertTotal(html, "70,00");

    // 2. orchid x 1
    browser.addOk(data.orchid, 1);
    html = browser.cartPage();
    assertEquals(2, Giro008Browser.rowCount(html), "rows after step 2");
    assertRow(html, ORCHID, "Vaso 12 cm", "1", "29,90", "29,90");
    assertTotal(html, "99,90");

    // 3. Medio x 1 again: one row, quantity 3
    browser.addOk(data.medio, 1);
    html = browser.cartPage();
    assertEquals(2, Giro008Browser.rowCount(html), "rows after step 3 (Medio must stay one row)");
    assertRow(html, BOUQUET, "Medio", "3", "35,00", "105,00");
    assertTotal(html, "134,90");

    // 4. orchid to 2
    long orchidItem = Giro008Browser.itemId(html, ORCHID);
    assertSeeOther(browser.update(orchidItem, "2"), "update orchid to 2");
    html = browser.cartPage();
    assertRow(html, ORCHID, "Vaso 12 cm", "2", "29,90", "59,80");
    assertTotal(html, "164,80");

    // 5. remove Medio
    long medioItem = Giro008Browser.itemId(html, BOUQUET);
    assertSeeOther(browser.remove(medioItem), "remove Medio");
    html = browser.cartPage();
    assertEquals(1, Giro008Browser.rowCount(html), "rows after step 5");
    assertFalse(html.contains("href=\"/prodotti/" + BOUQUET + "\""), "Medio still in the cart");
    assertRow(html, ORCHID, "Vaso 12 cm", "2", "29,90", "59,80");
    assertTotal(html, "59,80");

    // 6. orchid to 0: empty cart
    assertSeeOther(browser.update(orchidItem, "0"), "update orchid to 0");
    html = browser.cartPage();
    assertTrue(html.contains(EMPTY), "the cart is not empty after step 6");
    assertEquals(0, Giro008Browser.rowCount(html), "rows after step 6");
  }

  @Test
  void theFirstAddSetsTheCartCookieAndEachPostRenewsIt() {
    Giro008Browser browser = new Giro008Browser(DEMO);
    browser.page("/prodotti/" + BOUQUET, 200);

    Response first = browser.add(data.medio, "1");
    assertSeeOther(first, "first add");
    List<String> cookies = setCookies(first, CART_COOKIE);
    assertEquals(1, cookies.size(), "Set-Cookie cart on the first add: " + cookies);
    String cookie = cookies.get(0);
    String token = cookieValue(cookie);
    assertTrue(token.length() >= 43, "cart token shorter than 32 random bytes: " + token);
    assertTrue(hasAttribute(cookie, "HttpOnly"), "cart cookie without HttpOnly: " + cookie);
    assertTrue(hasAttribute(cookie, "SameSite=Lax"), "cart cookie without SameSite=Lax: " + cookie);
    assertTrue(hasAttribute(cookie, "Path=/"), "cart cookie without Path=/: " + cookie);
    assertTrue(hasAttribute(cookie, "Max-Age=604800"), "cart cookie without Max-Age: " + cookie);

    Response second = browser.add(data.medio, "1");
    assertSeeOther(second, "second add");
    List<String> renewed = setCookies(second, CART_COOKIE);
    assertEquals(1, renewed.size(), "Set-Cookie cart on the second add: " + renewed);
    assertEquals(token, cookieValue(renewed.get(0)), "the renewed cookie changed the token");
    assertTrue(
        hasAttribute(renewed.get(0), "Max-Age=604800"), "renewed without Max-Age: " + renewed);
  }

  @Test
  void unsellableVariantsAndBadQuantitiesAreRejectedAndTheCartIsUnchanged() {
    Giro008Browser browser = new Giro008Browser(DEMO);
    browser.page("/prodotti/" + ORCHID, 200);
    browser.addOk(data.orchid, 1);
    long orchidItem = Giro008Browser.itemId(browser.cartPage(), ORCHID);
    String before = browser.summary();

    Map<String, Long> notFound = new LinkedHashMap<>();
    notFound.put("inactive variant Extra", data.extra);
    notFound.put("inactive product Corona dell'Avvento", data.advent);
    notFound.put("hidden category Stella di Natale", data.poinsettia);
    notFound.put("variant of Fiori di Marco", data.marco);
    notFound.put("missing variant", 999_999_999L);
    for (Map.Entry<String, Long> c : notFound.entrySet()) {
      Response r = browser.add(c.getValue(), "1");
      assertEquals(404, r.statusCode(), c.getKey() + ": status");
      assertEquals(before, browser.summary(), c.getKey() + ": cart changed");
    }

    for (String q : List.of("0", "-1", "21", "abc")) {
      Response r = browser.add(data.medio, q);
      assertEquals(400, r.statusCode(), "add quantity=" + q + ": status");
      assertEquals(before, browser.summary(), "add quantity=" + q + ": cart changed");
    }
    for (String q : List.of("-1", "21", "abc")) {
      Response r = browser.update(orchidItem, q);
      assertEquals(400, r.statusCode(), "update quantity=" + q + ": status");
      assertEquals(before, browser.summary(), "update quantity=" + q + ": cart changed");
    }

    // Positive control: the same requests with valid values pass, so the token was good.
    assertSeeOther(browser.update(orchidItem, "1"), "update quantity=1");
    assertSeeOther(browser.add(data.medio, "1"), "add Medio quantity=1");
    String html = browser.cartPage();
    assertRow(html, BOUQUET, "Medio", "1", "35,00", "35,00");
    assertTotal(html, "64,90");
  }

  @Test
  void addsBeyondTwentyStopAtTwenty() {
    Giro008Browser browser = new Giro008Browser(DEMO);
    browser.page("/prodotti/" + BOUQUET, 200);
    browser.addOk(data.medio, 15);
    browser.addOk(data.medio, 10);
    String html = browser.cartPage();
    assertEquals(1, Giro008Browser.rowCount(html), "rows");
    assertRow(html, BOUQUET, "Medio", "20", "35,00", "700,00");
    browser.addOk(data.medio, 1);
    assertRow(browser.cartPage(), BOUQUET, "Medio", "20", "35,00", "700,00");
  }

  @Test
  void aPostWithoutAValidCsrfTokenIsRejectedAndTheCartIsUnchanged() {
    Giro008Browser browser = new Giro008Browser(DEMO);
    browser.page("/prodotti/" + BOUQUET, 200);
    browser.addOk(data.orchid, 1);
    String before = browser.summary();
    String token = browser.token();
    assertNotNull(browser.cookies.get(CSRF), "no " + CSRF + " cookie in the browser");
    Map<String, String> form = Map.of("variantId", Long.toString(data.medio), "quantity", "1");

    Response noField =
        browser.postRaw("/carrello/aggiungi", form, new LinkedHashMap<>(browser.cookies));
    assertEquals(400, noField.statusCode(), "POST without the CSRF field");
    assertEquals(before, browser.summary(), "cart changed after POST without the field");

    Map<String, String> noCsrfCookie = new LinkedHashMap<>(browser.cookies);
    noCsrfCookie.remove(CSRF);
    Response noCookie =
        browser.postRaw("/carrello/aggiungi", browser.withToken(form), noCsrfCookie);
    assertEquals(400, noCookie.statusCode(), "POST without the CSRF cookie");
    assertEquals(before, browser.summary(), "cart changed after POST without the cookie");

    Map<String, String> wrong = new LinkedHashMap<>(form);
    wrong.put(CSRF, "x" + token);
    Response mismatch =
        browser.postRaw("/carrello/aggiungi", wrong, new LinkedHashMap<>(browser.cookies));
    assertEquals(400, mismatch.statusCode(), "POST with a different CSRF value");
    assertEquals(before, browser.summary(), "cart changed after POST with a wrong token");

    assertSeeOther(browser.add(data.medio, "1"), "positive control with the right token");
  }

  @Test
  void tenParallelAddsOfTheSameVariantMakeOneRowOfTen() throws Exception {
    Giro008Browser browser = new Giro008Browser(DEMO);
    browser.page("/prodotti/" + BOUQUET, 200);
    browser.addOk(data.orchid, 1);
    assertNotNull(browser.cookies.get(CART_COOKIE), "no cart cookie after the first add");
    Map<String, String> cookies = new LinkedHashMap<>(browser.cookies);
    Map<String, String> form =
        browser.withToken(Map.of("variantId", Long.toString(data.medio), "quantity", "1"));

    int n = 10;
    CyclicBarrier barrier = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    List<Future<Integer>> results = new ArrayList<>();
    try {
      for (int i = 0; i < n; i++) {
        Giro008Browser thread = new Giro008Browser(DEMO);
        results.add(
            pool.submit(
                () -> {
                  barrier.await(30, TimeUnit.SECONDS);
                  return thread.postRaw("/carrello/aggiungi", form, cookies).statusCode();
                }));
      }
      for (Future<Integer> result : results) {
        assertEquals(303, result.get(60, TimeUnit.SECONDS), "status of a parallel add");
      }
    } finally {
      pool.shutdownNow();
    }

    String html = browser.cartPage();
    assertEquals(2, Giro008Browser.rowCount(html), "rows after the parallel adds");
    assertRow(html, BOUQUET, "Medio", "10", "35,00", "350,00");
    assertTotal(html, "379,90");
  }

  // --- helpers --------------------------------------------------------------------------------

  private static void assertRow(
      String html, String slug, String variant, String quantity, String unit, String subtotal) {
    String row = Giro008Browser.row(html, slug);
    assertTrue(row.contains(variant), slug + ": missing variant \"" + variant + "\" in " + row);
    assertEquals(quantity, Giro008Browser.quantity(row), slug + ": quantity");
    assertTrue(
        row.contains(unit + NBSP + "€"), slug + ": missing unit price " + unit + " in " + row);
    assertTrue(
        row.contains(subtotal + NBSP + "€"),
        slug + ": missing subtotal " + subtotal + " in " + row);
    assertTrue(row.contains("<label"), slug + ": no <label> for the quantity in " + row);
    long id = Giro008Browser.itemIdIn(row);
    assertTrue(
        Pattern.compile("action=[\"']/carrello/righe/" + id + "[\"']").matcher(row).find(),
        slug + ": no update form POST /carrello/righe/" + id);
  }

  private static void assertTotal(String html, String total) {
    assertEquals(total + NBSP + "€", Giro008Browser.total(html), "Totale prodotti");
    assertTrue(
        html.contains("IVA inclusa. Consegna calcolata al checkout."), "missing the VAT note");
  }

  private static String hiddenValue(String html, String name) {
    Matcher input =
        Pattern.compile("<input[^>]*name=[\"']" + Pattern.quote(name) + "[\"'][^>]*>")
            .matcher(html);
    assertTrue(input.find(), "no input named " + name + " in " + html);
    Matcher value = Pattern.compile("value=[\"']([^\"']*)[\"']").matcher(input.group());
    assertTrue(value.find(), "input " + name + " without value: " + input.group());
    return value.group(1);
  }
}
