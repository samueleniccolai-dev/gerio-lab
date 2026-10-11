package it.gerio.platform.catalog;

import java.util.Locale;

/** The one place that turns cents into text: {@code 2500} becomes {@code "25,00 €"} (U+00A0). */
public final class Prices {

  private Prices() {}

  public static String format(long cents) {
    // Built by hand: the output must not change with the JDK's locale data.
    long absolute = Math.abs(cents);
    String sign = cents < 0 ? "-" : "";
    return String.format(Locale.ITALY, "%s%d,%02d €", sign, absolute / 100, absolute % 100);
  }

  /** Catalog form: the cheapest active variant, as in {@code "da 25,00 €"}. */
  public static String from(long cents) {
    return "da " + format(cents);
  }
}
