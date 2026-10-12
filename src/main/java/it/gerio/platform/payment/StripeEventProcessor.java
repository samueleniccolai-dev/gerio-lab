package it.gerio.platform.payment;

import it.gerio.platform.cart.CartService;
import it.gerio.platform.order.CustomerOrder;
import it.gerio.platform.order.OrderLookup;
import it.gerio.platform.order.OrderStateMachine;
import it.gerio.platform.order.OrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Applies one Stripe event to the order of the shop in the tenant context. A bean of its own, in a
 * new transaction: Hibernate fixes the shop when the session opens, so the work must start after
 * the webhook has switched the context to the shop (SPEC 5.2).
 */
@ApplicationScoped
public class StripeEventProcessor {

  private static final Logger LOG = Logger.getLogger(StripeEventProcessor.class);

  /** The fields of the Checkout session that matter, read from the raw JSON. */
  public record SessionData(
      String id,
      String status,
      String paymentStatus,
      String paymentIntent,
      String clientReferenceId,
      String metadataTenantId) {}

  @Inject OrderLookup orders;
  @Inject OrderStateMachine stateMachine;
  @Inject CartService cartService;
  @Inject StripeEventLog eventLog;

  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void process(String eventId, String type, SessionData session) {
    Optional<CustomerOrder> found =
        orders
            .lockedByReference(session.clientReferenceId())
            .filter(order -> Objects.equals(session.metadataTenantId(), order.getTenantId()));
    if (found.isPresent()) {
      CustomerOrder order = found.get();
      switch (type) {
        case StripeWebhookResource.COMPLETED -> completed(eventId, order, session);
        case StripeWebhookResource.EXPIRED -> expired(eventId, order, session);
        default -> LOG.infof("Event %s (%s): nothing to do", eventId, type);
      }
    } else {
      LOG.infof("Event %s (%s): no order of this shop", eventId, type);
    }
    eventLog.markProcessed(eventId);
  }

  private void completed(String eventId, CustomerOrder order, SessionData session) {
    if (!"complete".equals(session.status()) || !"paid".equals(session.paymentStatus())) {
      LOG.infof("Event %s: order %d not paid (session not paid yet)", eventId, order.getId());
      return;
    }
    boolean wasPending = order.status == OrderStatus.PENDING_PAYMENT;
    if (stateMachine.markPaid(order, session.id(), session.paymentIntent())) {
      if (order.cartId != null) {
        cartService.deleteItems(order.cartId);
      }
    } else if (!wasPending
        && order.status == OrderStatus.PAID
        && !Objects.equals(order.stripePaymentIntentId, session.paymentIntent())) {
      LOG.warnf("Event %s: order %d already paid, a second payment came", eventId, order.getId());
    }
  }

  private void expired(String eventId, CustomerOrder order, SessionData session) {
    if (!Objects.equals(order.stripeCheckoutSessionId, session.id())) {
      LOG.infof("Event %s: order %d has a newer session, not expired", eventId, order.getId());
      return;
    }
    stateMachine.expire(order);
  }
}
