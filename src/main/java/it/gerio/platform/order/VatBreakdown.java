package it.gerio.platform.order;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Taxable amount and tax per VAT rate of an order whose prices include VAT (SPEC 6, {@code
 * vat_breakdown}). Pure arithmetic in cents: tax = gross * rate / (10000 + rate), rounded half up,
 * computed once per rate on the sum of its lines.
 */
final class VatBreakdown {

  /** One line: the rate in basis points and the line total with VAT, in cents. */
  record Line(int rateBp, long grossCents) {}

  private VatBreakdown() {}

  /**
   * JSON array ordered by rate, e.g. {@code [{"rate_bp":1000,"taxable_cents":..,"tax_cents":..}]}.
   */
  static String toJson(List<Line> lines) {
    Map<Integer, Long> grossByRate = new TreeMap<>();
    for (Line line : lines) {
      grossByRate.merge(line.rateBp(), line.grossCents(), Long::sum);
    }
    StringBuilder json = new StringBuilder("[");
    for (Map.Entry<Integer, Long> entry : grossByRate.entrySet()) {
      long rate = entry.getKey();
      long gross = entry.getValue();
      long tax = (gross * rate + (10_000 + rate) / 2) / (10_000 + rate);
      if (json.length() > 1) {
        json.append(',');
      }
      json.append("{\"rate_bp\":")
          .append(rate)
          .append(",\"taxable_cents\":")
          .append(gross - tax)
          .append(",\"tax_cents\":")
          .append(tax)
          .append('}');
    }
    return json.append(']').toString();
  }
}
