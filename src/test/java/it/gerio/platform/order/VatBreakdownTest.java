package it.gerio.platform.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.gerio.platform.order.VatBreakdown.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

class VatBreakdownTest {

  @Test
  void splitsOneRateIntoTaxableAndTax() {
    // 10480 with 10% VAT included: tax = 10480 * 1000 / 11000 = 952.7 -> 953.
    assertEquals(
        "[{\"rate_bp\":1000,\"taxable_cents\":9527,\"tax_cents\":953}]",
        VatBreakdown.toJson(List.of(new Line(1000, 4500), new Line(1000, 5980))));
  }

  @Test
  void groupsByRateInOrder() {
    assertEquals(
        "[{\"rate_bp\":400,\"taxable_cents\":962,\"tax_cents\":38},"
            + "{\"rate_bp\":2200,\"taxable_cents\":820,\"tax_cents\":180}]",
        VatBreakdown.toJson(List.of(new Line(2200, 1000), new Line(400, 1000))));
  }

  @Test
  void taxPlusTaxableIsAlwaysTheTotal() {
    for (long gross = 0; gross < 5000; gross += 37) {
      String json = VatBreakdown.toJson(List.of(new Line(1000, gross)));
      String taxable = json.replaceAll(".*taxable_cents\":(\\d+).*", "$1");
      String tax = json.replaceAll(".*tax_cents\":(\\d+).*", "$1");
      assertEquals(gross, Long.parseLong(taxable) + Long.parseLong(tax));
    }
  }
}
