package it.gerio.platform.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import it.gerio.platform.common.TestClock;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Payment transitions leave every state but {@code PENDING_PAYMENT} untouched (SPEC 8.4). */
class OrderStateMachineTest {

  private final OrderStateMachine machine = new OrderStateMachine();

  OrderStateMachineTest() {
    machine.clock = new TestClock(Instant.parse("2026-10-12T08:00:00Z"));
  }

  private static CustomerOrder order(OrderStatus status) {
    CustomerOrder order = new CustomerOrder();
    order.status = status;
    order.stripeCheckoutSessionId = "cs_old";
    return order;
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"PAID", "EXPIRED"})
  void markPaidDoesNothingOutsidePendingPayment(OrderStatus status) {
    CustomerOrder order = order(status);

    assertFalse(machine.markPaid(order, "cs_new", "pi_new"));

    assertEquals(status, order.status);
    assertEquals("cs_old", order.stripeCheckoutSessionId);
    assertNull(order.stripePaymentIntentId);
    assertNull(order.paidAt);
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"PAID", "EXPIRED"})
  void expireDoesNothingOutsidePendingPayment(OrderStatus status) {
    CustomerOrder order = order(status);

    assertFalse(machine.expire(order));

    assertEquals(status, order.status);
  }
}
