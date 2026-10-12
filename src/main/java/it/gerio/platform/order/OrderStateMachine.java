package it.gerio.platform.order;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;

/**
 * The only place where an order changes state (SPEC 7): every change writes an {@link OrderEvent}.
 * Payment transitions start only from {@code PENDING_PAYMENT}; from any other state they do nothing
 * and say so, which makes repeated or out-of-order Stripe events harmless (SPEC 8.4).
 */
@ApplicationScoped
public class OrderStateMachine {

  @Inject Clock clock;

  /** A new order enters {@code PENDING_PAYMENT}: it is saved here, with its first event. */
  public void start(CustomerOrder order) {
    order.status = OrderStatus.PENDING_PAYMENT;
    order.persist();
    record(order, null, OrderStatus.PENDING_PAYMENT);
  }

  /**
   * The payment arrived on {@code sessionId} (the session that paid, maybe not the current one).
   *
   * @return true if the order changed, false if it was not waiting for payment
   */
  public boolean markPaid(CustomerOrder order, String sessionId, String paymentIntentId) {
    if (order.status != OrderStatus.PENDING_PAYMENT) {
      return false;
    }
    order.status = OrderStatus.PAID;
    order.stripeCheckoutSessionId = sessionId;
    order.stripePaymentIntentId = paymentIntentId;
    order.paidAt = clock.instant();
    record(order, OrderStatus.PENDING_PAYMENT, OrderStatus.PAID);
    return true;
  }

  /**
   * The payment session ran out without payment.
   *
   * @return true if the order changed, false if it was not waiting for payment
   */
  public boolean expire(CustomerOrder order) {
    if (order.status != OrderStatus.PENDING_PAYMENT) {
      return false;
    }
    order.status = OrderStatus.EXPIRED;
    record(order, OrderStatus.PENDING_PAYMENT, OrderStatus.EXPIRED);
    return true;
  }

  private void record(CustomerOrder order, OrderStatus from, OrderStatus to) {
    OrderEvent event = new OrderEvent();
    event.orderId = order.getId();
    event.fromStatus = from == null ? null : from.name();
    event.toStatus = to.name();
    event.at = clock.instant();
    event.persist();
  }
}
