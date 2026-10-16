package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Giro 017 test helpers (docs/lab/giri/017-piano.md): the delivery form with the invented recipient
 * of the plan, carts of the demo, readers of the checkout page and the texts the plan fixes.
 */
final class Giro017Checkout {

  static final String RECIPIENT = "Anna Verdi";
  static final String RECIPIENT_PHONE = "+39 333 999 0007";
  static final String ADDRESS = "Via delle Prove 7";
  static final String NOTES = "Citofono Verdi, secondo piano";

  static final String OUT_OF_ZONE = "Non consegniamo a questo indirizzo.";
  static final String WHATSAPP_LINK = "https://wa.me/393330000000";
  static final String WHATSAPP_TEXT = "Scrivici su WhatsApp";
  static final String CUTOFF =
      "Gli ordini per oggi si chiudono alle 12:00: scegli un altro giorno.";
  static final String GENERIC_DATE = "valido, dal";

  static final String MISSING_RECIPIENT = "Scrivi il nome del destinatario.";
  static final String MISSING_RECIPIENT_PHONE = "Scrivi il telefono del destinatario.";
  static final String MISSING_ADDRESS = "Scrivi l'indirizzo di consegna.";
  static final String BAD_POSTAL_CODE = "Scrivi un CAP di 5 cifre.";
  static final String MISSING_CITY = "Scrivi il comune.";

  private static final Pattern PICKUP_DATE_INPUT =
      Pattern.compile("<input[^>]*name=[\"']pickupDate[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern MIN = Pattern.compile("\\smin=[\"']([^\"']*)[\"']");

  private Giro017Checkout() {}

  /** «Per la zona <zone> l'ordine minimo è <amount>.» */
  static String minimum(String zone, String amount) {
    return "Per la zona " + zone + " l'ordine minimo è " + eur(amount) + ".";
  }

  /** The amount as the shop shows it: comma, NBSP, euro sign. */
  static String eur(String amount) {
    return amount + Giro008Browser.NBSP + "€";
  }

  /** The amount, not as the tail of a larger one ("5,00 €" is not found in "45,00 €"). */
  static boolean hasAmount(String text, String amount) {
    return Pattern.compile("(?<![\\d.])" + Pattern.quote(eur(amount))).matcher(text).find();
  }

  /** The page with the entities Qute writes for quotes and ampersands decoded. */
  static String decoded(String html) {
    return html.replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&amp;", "&");
  }

  static String decoded(Response response) {
    return decoded(Giro008Browser.body(response));
  }

  /** The delivery form of the plan: Giulia orders for Anna Verdi, Via delle Prove 7. */
  static Map<String, String> delivery(String date, String postalCode, String city) {
    Map<String, String> form = new LinkedHashMap<>(Giro010Checkout.form(date));
    form.put("fulfillmentType", "DELIVERY");
    form.put("recipientName", RECIPIENT);
    form.put("recipientPhone", RECIPIENT_PHONE);
    form.put("addressLine", ADDRESS);
    form.put("postalCode", postalCode);
    form.put("city", city);
    form.put("deliveryNotes", NOTES);
    return form;
  }

  static Map<String, String> pickup(String date) {
    Map<String, String> form = new LinkedHashMap<>(Giro010Checkout.form(date));
    form.put("fulfillmentType", "PICKUP");
    return form;
  }

  /** A demo browser with 1 x 12 red roses (4500) and the checkout page loaded. */
  static Giro008Browser roses(Giro010CheckoutData data) {
    Giro008Browser browser = new Giro008Browser(Giro008CartData.DEMO);
    browser.page("/prodotti/rose-rosse", 200);
    browser.addOk(data.rose12, 1);
    browser.page("/checkout", 200);
    return browser;
  }

  /** A demo browser with 1 x white orchid (2990) and the checkout page loaded. */
  static Giro008Browser orchid(Giro010CheckoutData data) {
    Giro008Browser browser = new Giro008Browser(Giro008CartData.DEMO);
    browser.page("/prodotti/orchidea-bianca", 200);
    browser.addOk(data.cart.orchid, 1);
    browser.page("/checkout", 200);
    return browser;
  }

  /** A demo browser with 1 x seasonal bouquet «Medio» (3500) and the checkout page loaded. */
  static Giro008Browser medium(Giro010CheckoutData data) {
    Giro008Browser browser = new Giro008Browser(Giro008CartData.DEMO);
    browser.page("/prodotti/bouquet-di-stagione", 200);
    browser.addOk(data.cart.medio, 1);
    browser.page("/checkout", 200);
    return browser;
  }

  /** A browser of Marco's shop with 1 x his bouquet (3000) and the checkout page loaded. */
  static Giro008Browser marco(Giro010CheckoutData data) {
    Giro008Browser browser = new Giro008Browser(Giro008CartData.MARCO);
    browser.page("/prodotti/bouquet-di-stagione", 200);
    browser.addOk(data.cart.marco, 1);
    browser.page("/checkout", 200);
    return browser;
  }

  /** The {@code min} attribute of the pickupDate input (on the input tag itself). */
  static String minDate(String html) {
    Matcher input = PICKUP_DATE_INPUT.matcher(html);
    assertTrue(input.find(), "no <input name=pickupDate>");
    Matcher min = MIN.matcher(input.group());
    assertTrue(min.find(), "the pickupDate input has no min: " + input.group());
    return min.group(1);
  }

  /** A 400 that shows the form again and contains the given text. */
  static String assertRejected(Response response, String expected, String what) {
    String html = decoded(response);
    assertEquals(400, response.statusCode(), what + ": status; body: " + html);
    assertTrue(html.contains("action=\"/checkout\""), what + ": the form is not shown again");
    assertTrue(html.contains(expected), what + ": no «" + expected + "»");
    return html;
  }

  /** The Stripe line whose name is exactly the given one. */
  static Map<String, String> exactLine(List<Map<String, String>> lines, String name) {
    for (Map<String, String> line : lines) {
      if (name.equals(line.get("price_data][product_data][name"))) {
        return line;
      }
    }
    throw new AssertionError("no Stripe line item named «" + name + "»: " + lines);
  }

  static void assertDeliveryLine(List<Map<String, String>> lines, String zone, long cents) {
    Map<String, String> line = exactLine(lines, "Consegna — " + zone);
    assertEquals("eur", line.get("price_data][currency"), "delivery line: currency");
    assertEquals(
        Long.toString(cents), line.get("price_data][unit_amount"), "delivery line: amount");
    assertEquals("1", line.get("quantity"), "delivery line: quantity");
  }
}
