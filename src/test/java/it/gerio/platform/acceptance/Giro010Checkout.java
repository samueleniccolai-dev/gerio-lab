package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Giro 010 test helpers: the checkout form of the plan and readers of the Stripe parameters. */
final class Giro010Checkout {

  static final String NAME = "Giulia Bianchi";
  static final String EMAIL = "giulia.bianchi@example.com";
  static final String PHONE = "+39 333 000 1234";
  static final String PAY = "Ordina e paga";

  private static final Pattern LINE_KEY = Pattern.compile("line_items\\[(\\d+)]\\[(.+)]");

  private Giro010Checkout() {}

  /** The valid form of the plan (Giulia Bianchi, pickup on the given ISO date). */
  static Map<String, String> form(String pickupDate) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("customerName", NAME);
    form.put("customerEmail", EMAIL);
    form.put("customerPhone", PHONE);
    form.put("pickupDate", pickupDate);
    return form;
  }

  static Map<String, String> with(Map<String, String> form, String name, String value) {
    Map<String, String> changed = new LinkedHashMap<>(form);
    changed.put(name, value);
    return changed;
  }

  /** 1 x 12 red roses + 2 x white orchid on the demo: 4500 + 5980 = 10480. */
  static void fillDemoCart(Giro008Browser browser, Giro010CheckoutData data) {
    browser.page("/prodotti/rose-rosse", 200);
    browser.addOk(data.rose12, 1);
    browser.addOk(data.cart.orchid, 2);
  }

  /** Asserts a 303 to the Stripe URL of the given session. */
  static void assertToStripe(Response response, String sessionId, String what) {
    assertEquals(
        303, response.statusCode(), what + ": status; body: " + Giro008Browser.body(response));
    String location = response.getHeader("Location");
    assertNotNull(location, what + ": no Location");
    assertEquals(Giro010FakeStripe.urlOf(sessionId), location, what + ": Location");
  }

  /** The line_items[i][...] parameters grouped by line, in index order. */
  static List<Map<String, String>> lineItems(Map<String, String> form) {
    TreeMap<Integer, Map<String, String>> lines = new TreeMap<>();
    for (Map.Entry<String, String> e : form.entrySet()) {
      Matcher m = LINE_KEY.matcher(e.getKey());
      if (m.matches()) {
        lines
            .computeIfAbsent(Integer.parseInt(m.group(1)), i -> new LinkedHashMap<>())
            .put(m.group(2), e.getValue());
      }
    }
    return new ArrayList<>(lines.values());
  }

  /** The line whose product name contains the given text. */
  static Map<String, String> line(List<Map<String, String>> lines, String nameContains) {
    for (Map<String, String> line : lines) {
      String name = line.get("price_data][product_data][name");
      if (name != null && name.contains(nameContains)) {
        return line;
      }
    }
    throw new AssertionError("no Stripe line item named like \"" + nameContains + "\": " + lines);
  }

  static void assertLine(
      List<Map<String, String>> lines, String product, String variant, long cents, int quantity) {
    Map<String, String> line = line(lines, product);
    String name = line.get("price_data][product_data][name");
    assertTrue(name.contains(variant), "line " + product + ": name without variant: " + name);
    assertEquals("eur", line.get("price_data][currency"), "line " + product + ": currency");
    assertEquals(
        Long.toString(cents), line.get("price_data][unit_amount"), "line " + product + ": amount");
    assertEquals(Integer.toString(quantity), line.get("quantity"), "line " + product + ": qty");
  }
}
