package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Giro 008 test helper: a minimal browser for one shop host. It keeps the cookies it receives,
 * sends them back, and takes the CSRF token from the hidden field of the last page with a form.
 * Black box: only HTTP, no production classes.
 */
final class Giro008Browser {

  static final String CART_COOKIE = "cart";
  static final String CSRF = "csrf-token";
  static final String NBSP = " ";

  private static final Pattern CSRF_INPUT =
      Pattern.compile("<input[^>]*name=[\"']" + CSRF + "[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern VALUE = Pattern.compile("value=[\"']([^\"']*)[\"']");
  private static final Pattern PRODUCT_LINK =
      Pattern.compile("href=[\"']/prodotti/([a-z0-9-]+)[\"']");
  private static final Pattern REMOVE_ACTION =
      Pattern.compile("action=[\"']/carrello/righe/(\\d+)/rimuovi[\"']");
  private static final Pattern QUANTITY_INPUT =
      Pattern.compile("<input[^>]*name=[\"']quantity[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern AMOUNT =
      Pattern.compile("\\d{1,3}(?:\\.\\d{3})*,\\d{2}" + NBSP + "€");

  final String host;
  final Map<String, String> cookies = new LinkedHashMap<>();
  private String formToken;

  Giro008Browser(String host) {
    this.host = host;
  }

  // --- requests -------------------------------------------------------------------------------

  Response get(String path) {
    Response response =
        given().redirects().follow(false).header("Host", host).cookies(cookies).when().get(path);
    absorb(response);
    String body = body(response);
    Matcher input = CSRF_INPUT.matcher(body);
    if (input.find()) {
      Matcher value = VALUE.matcher(input.group());
      if (value.find()) {
        formToken = value.group(1);
      }
    }
    return response;
  }

  String page(String path, int status) {
    Response response = get(path);
    assertEquals(status, response.statusCode(), "status of GET " + host + path);
    return body(response);
  }

  /** POST with the given form and cookies, exactly as given (no token added). */
  Response postRaw(String path, Map<String, String> form, Map<String, String> sentCookies) {
    Response response =
        given()
            .redirects()
            .follow(false)
            .header("Host", host)
            .cookies(sentCookies)
            .contentType("application/x-www-form-urlencoded")
            .formParams(form)
            .when()
            .post(path);
    absorb(response);
    return response;
  }

  /** POST with the CSRF token taken from the last page (a cart page is loaded if none yet). */
  Response post(String path, Map<String, String> form) {
    return postRaw(path, withToken(form), new LinkedHashMap<>(cookies));
  }

  Map<String, String> withToken(Map<String, String> form) {
    Map<String, String> full = new LinkedHashMap<>(form);
    full.put(CSRF, token());
    return full;
  }

  String token() {
    if (formToken == null) {
      get("/carrello");
    }
    assertNotNull(formToken, "no hidden " + CSRF + " field on the pages of " + host);
    return formToken;
  }

  Response add(long variantId, String quantity) {
    return post(
        "/carrello/aggiungi", Map.of("variantId", Long.toString(variantId), "quantity", quantity));
  }

  Response update(long itemId, String quantity) {
    return post("/carrello/righe/" + itemId, Map.of("quantity", quantity));
  }

  Response remove(long itemId) {
    return post("/carrello/righe/" + itemId + "/rimuovi", Map.of());
  }

  void addOk(long variantId, int quantity) {
    assertSeeOther(add(variantId, Integer.toString(quantity)), "add " + variantId);
  }

  // --- cart page ------------------------------------------------------------------------------

  String cartPage() {
    return page("/carrello", 200);
  }

  /** Rows and total of the cart page, as text: what "the cart is unchanged" compares. */
  String summary() {
    String html = cartPage();
    if (html.contains("Il tuo carrello è vuoto.")) {
      return "vuoto";
    }
    StringBuilder text = new StringBuilder();
    for (String slug : slugs(html)) {
      String row = row(html, slug);
      text.append(slug)
          .append(" q=")
          .append(quantity(row))
          .append(" id=")
          .append(itemIdIn(row))
          .append(" ")
          .append(amounts(row))
          .append(" | ");
    }
    return text.append("totale ").append(total(html)).toString();
  }

  static List<String> slugs(String html) {
    List<String> slugs = new ArrayList<>();
    Matcher m = PRODUCT_LINK.matcher(main(html));
    while (m.find()) {
      if (!slugs.contains(m.group(1))) {
        slugs.add(m.group(1));
      }
    }
    return slugs;
  }

  /** Number of rows: one "Rimuovi" form per row. */
  static int rowCount(String html) {
    int count = 0;
    Matcher m = REMOVE_ACTION.matcher(html);
    while (m.find()) {
      count++;
    }
    return count;
  }

  /** The part of the page from the product link to the next other product or the total. */
  static String row(String html, String slug) {
    String main = main(html);
    Matcher m =
        Pattern.compile("href=[\"']/prodotti/" + Pattern.quote(slug) + "[\"']").matcher(main);
    assertTrue(m.find(), "no cart row with a link to /prodotti/" + slug);
    int start = m.start();
    int end = main.indexOf("Totale prodotti", start);
    if (end < 0) {
      end = main.length();
    }
    Matcher next = PRODUCT_LINK.matcher(main);
    next.region(m.end(), end);
    while (next.find()) {
      if (!next.group(1).equals(slug)) {
        end = next.start();
        break;
      }
    }
    return main.substring(start, end);
  }

  static long itemId(String html, String slug) {
    return itemIdIn(row(html, slug));
  }

  static long itemIdIn(String row) {
    Matcher m = REMOVE_ACTION.matcher(row);
    assertTrue(m.find(), "no form POST /carrello/righe/{id}/rimuovi in the row: " + row);
    return Long.parseLong(m.group(1));
  }

  static String quantity(String row) {
    Matcher input = QUANTITY_INPUT.matcher(row);
    assertTrue(input.find(), "no quantity input in the row: " + row);
    Matcher value = VALUE.matcher(input.group());
    assertTrue(value.find(), "the quantity input has no value: " + input.group());
    return value.group(1);
  }

  static List<String> amounts(String text) {
    List<String> found = new ArrayList<>();
    Matcher m = AMOUNT.matcher(text);
    while (m.find()) {
      found.add(m.group());
    }
    return found;
  }

  static String total(String html) {
    int at = html.indexOf("Totale prodotti");
    assertTrue(at >= 0, "missing \"Totale prodotti\"");
    Matcher m = AMOUNT.matcher(html);
    assertTrue(m.find(at), "no amount after \"Totale prodotti\"");
    return m.group();
  }

  private static String main(String html) {
    int start = html.indexOf("<main");
    return start >= 0 ? html.substring(start) : html;
  }

  // --- cookies --------------------------------------------------------------------------------

  static List<String> setCookies(Response response, String name) {
    List<String> found = new ArrayList<>();
    for (String header : response.getHeaders().getValues("Set-Cookie")) {
      if (header.startsWith(name + "=")) {
        found.add(header);
      }
    }
    return found;
  }

  static String cookieValue(String setCookie) {
    int eq = setCookie.indexOf('=');
    int semi = setCookie.indexOf(';');
    return setCookie.substring(eq + 1, semi < 0 ? setCookie.length() : semi);
  }

  static boolean hasAttribute(String setCookie, String attribute) {
    for (String part : setCookie.split(";")) {
      if (part.trim().toLowerCase(Locale.ROOT).equals(attribute.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private void absorb(Response response) {
    for (String header : response.getHeaders().getValues("Set-Cookie")) {
      int eq = header.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String name = header.substring(0, eq);
      String value = cookieValue(header);
      if (hasAttribute(header, "Max-Age=0") || value.isEmpty()) {
        cookies.remove(name);
      } else {
        cookies.put(name, value);
      }
    }
  }

  // --- assertions -----------------------------------------------------------------------------

  static String body(Response response) {
    return new String(response.asByteArray(), StandardCharsets.UTF_8);
  }

  static void assertSeeOther(Response response, String what) {
    assertEquals(303, response.statusCode(), what + ": status; body: " + body(response));
    String location = response.getHeader("Location");
    assertNotNull(location, what + ": no Location");
    assertTrue(location.matches("(https?://[^/]+)?/carrello"), what + ": Location is " + location);
  }
}
