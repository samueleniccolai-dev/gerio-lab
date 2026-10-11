package it.gerio.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PricesTest {

  @Test
  void formatsCentsWithCommaAndNoBreakSpaceBeforeEuro() {
    assertEquals("25,00 €", Prices.format(2500));
    assertEquals("29,90 €", Prices.format(2990));
    assertEquals("0,05 €", Prices.format(5));
    assertEquals("1234,56 €", Prices.format(123456));
  }

  @Test
  void fromPriceUsesAnOrdinarySpaceAfterDa() {
    assertEquals("da 25,00 €", Prices.from(2500));
  }
}
