package it.gerio.platform.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import it.gerio.platform.payment.StripeCheckout;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** A late Stripe answer never writes on an order that is no longer waiting for payment. */
class CheckoutSessionSaveTest {

  private static CustomerOrder order(OrderStatus status) {
    CustomerOrder order = new CustomerOrder();
    order.status = status;
    order.checkoutAttempt = 2;
    order.stripeCheckoutSessionId = "cs_paid";
    order.checkoutUrl = null;
    order.checkoutExpiresAt = Instant.parse("2026-10-12T09:00:00Z");
    return order;
  }

  @Test
  void savesTheSessionOfThePendingAttempt() {
    CustomerOrder order = order(OrderStatus.PENDING_PAYMENT);

    CheckoutService.applySession(order, 2, new StripeCheckout.Session("cs_new", "https://x/new"));

    assertEquals("cs_new", order.stripeCheckoutSessionId);
    assertEquals("https://x/new", order.checkoutUrl);
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"PAID", "EXPIRED"})
  void doesNotSaveTheSessionOutsidePendingPayment(OrderStatus status) {
    CustomerOrder order = order(status);

    CheckoutService.applySession(order, 2, new StripeCheckout.Session("cs_new", "https://x/new"));

    assertEquals("cs_paid", order.stripeCheckoutSessionId);
    assertNull(order.checkoutUrl);
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"PAID", "EXPIRED"})
  void doesNotMarkFailedOutsidePendingPayment(OrderStatus status) {
    CustomerOrder order = order(status);

    CheckoutService.applyFailure(order, 2);

    assertEquals(Instant.parse("2026-10-12T09:00:00Z"), order.checkoutExpiresAt);
  }

  @Test
  void marksFailedThePendingAttempt() {
    CustomerOrder order = order(OrderStatus.PENDING_PAYMENT);

    CheckoutService.applyFailure(order, 2);

    assertNull(order.checkoutExpiresAt);
  }
}
