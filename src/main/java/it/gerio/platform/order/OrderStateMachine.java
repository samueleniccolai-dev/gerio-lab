package it.gerio.platform.order;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;

/**
 * The only place where an order changes state (SPEC 7): every change writes an {@link OrderEvent}.
 * For now there is only the first one; the others come with the Stripe webhook.
 */
@ApplicationScoped
public class OrderStateMachine {

  @Inject Clock clock;

  /** A new order enters {@code PENDING_PAYMENT}: it is saved here, with its first event. */
  public void start(CustomerOrder order) {
    order.status = OrderStatus.PENDING_PAYMENT;
    order.persist();
    OrderEvent event = new OrderEvent();
    event.orderId = order.getId();
    event.fromStatus = null;
    event.toStatus = OrderStatus.PENDING_PAYMENT.name();
    event.at = clock.instant();
    event.persist();
  }
}
