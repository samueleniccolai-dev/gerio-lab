package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.acceptance.Giro008Browser.CART_COOKIE;
import static it.gerio.platform.acceptance.Giro008Browser.NBSP;
import static it.gerio.platform.acceptance.Giro008Browser.assertSeeOther;
import static it.gerio.platform.acceptance.Giro008Browser.cookieValue;
import static it.gerio.platform.acceptance.Giro008Browser.setCookies;
import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro008CartData.MARCO;
import static it.gerio.platform.acceptance.Giro008CartData.PLATFORM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 008 (goal T4): isolation of carts. A cart is reached only with its own cookie on its own
 * shop: another browser of the same shop, another shop, or an invented token never see or change
 * it. Black box over HTTP.
 */
@QuarkusTest
class Giro008CartIsolationAcceptanceTest {

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
  void anotherBrowserOfTheSameShopCannotChangeOrRemoveARow() {
    Giro008Browser first = new Giro008Browser(DEMO);
    first.page("/prodotti/" + BOUQUET, 200);
    first.addOk(data.medio, 2);
    long firstItem = Giro008Browser.itemId(first.cartPage(), BOUQUET);
    String firstBefore = first.summary();

    Giro008Browser second = new Giro008Browser(DEMO);
    second.page("/prodotti/" + ORCHID, 200);
    second.addOk(data.orchid, 1);
    String secondBefore = second.summary();
    assertNotEquals(
        first.cookies.get(CART_COOKIE), second.cookies.get(CART_COOKIE), "same cart cookie");

    assertEquals(404, second.update(firstItem, "5").statusCode(), "update of another cart's row");
    assertEquals(404, second.remove(firstItem).statusCode(), "removal of another cart's row");

    assertEquals(firstBefore, first.summary(), "the first cart changed");
    assertEquals(secondBefore, second.summary(), "the second cart changed");
  }

  @Test
  void theDemoCartIsInvisibleAndUntouchableFromAnotherShop() {
    Giro008Browser demo = new Giro008Browser(DEMO);
    demo.page("/prodotti/" + BOUQUET, 200);
    demo.addOk(data.medio, 2);
    long demoItem = Giro008Browser.itemId(demo.cartPage(), BOUQUET);
    String demoBefore = demo.summary();
    String demoToken = demo.cookies.get(CART_COOKIE);
    assertNotNull(demoToken, "no cart cookie on demo");

    Giro008Browser marco = new Giro008Browser(MARCO);
    marco.page("/prodotti/" + BOUQUET, 200);
    marco.cookies.put(CART_COOKIE, demoToken);

    String marcoCart = marco.cartPage();
    assertTrue(marcoCart.contains(EMPTY), "Marco shows the demo cart");
    assertFalse(marcoCart.contains("Medio"), "Marco shows the demo row");

    assertEquals(404, marco.update(demoItem, "5").statusCode(), "Marco updates a demo row");
    assertEquals(404, marco.remove(demoItem).statusCode(), "Marco removes a demo row");

    Response add = marco.add(data.marco, "1");
    assertSeeOther(add, "Marco adds its own variant with the demo cookie");
    List<String> cookies = setCookies(add, CART_COOKIE);
    assertEquals(1, cookies.size(), "Set-Cookie cart on Marco: " + cookies);
    assertNotEquals(demoToken, cookieValue(cookies.get(0)), "Marco reused the demo token");

    String marcoAfter = marco.cartPage();
    assertTrue(marcoAfter.contains("Unico"), "Marco's cart lacks its variant");
    assertTrue(marcoAfter.contains("30,00" + NBSP + "€"), "Marco's cart lacks its price");
    assertFalse(marcoAfter.contains("Medio"), "Marco's cart shows the demo row");

    assertEquals(demoBefore, demo.summary(), "the demo cart changed");
  }

  @Test
  void anInventedTokenIsAnEmptyCartAndTheFirstAddIssuesANewOne() {
    String invented = "inventato-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    Giro008Browser browser = new Giro008Browser(DEMO);
    browser.page("/prodotti/" + BOUQUET, 200);
    browser.cookies.put(CART_COOKIE, invented);

    assertTrue(browser.cartPage().contains(EMPTY), "an invented token shows a non-empty cart");

    Response add = browser.add(data.medio, "1");
    assertSeeOther(add, "add with an invented token");
    List<String> cookies = setCookies(add, CART_COOKIE);
    assertEquals(1, cookies.size(), "Set-Cookie cart after the add: " + cookies);
    assertNotEquals(invented, cookieValue(cookies.get(0)), "the invented token was adopted");

    String html = browser.cartPage();
    assertEquals(1, Giro008Browser.rowCount(html), "rows in the new cart");
    assertTrue(Giro008Browser.row(html, BOUQUET).contains("Medio"), "Medio missing");
  }

  @Test
  void thePlatformDomainHasNoCart() {
    Response response =
        given().redirects().follow(false).header("Host", PLATFORM).when().get("/carrello");
    assertEquals(404, response.statusCode(), "GET /carrello on the platform domain");
  }
}
