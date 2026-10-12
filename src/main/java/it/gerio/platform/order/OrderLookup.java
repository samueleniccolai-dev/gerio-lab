package it.gerio.platform.order;

import it.gerio.platform.catalog.Prices;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.Optional;

/**
 * Finds orders for the Stripe webhook and for the page where the customer comes back. Hibernate
 * limits every query to the shop of the request, so an id of another shop is simply not found.
 */
@ApplicationScoped
public class OrderLookup {

  /** The page reloads itself every 2 seconds up to this many times (30 seconds). */
  static final int MAX_RELOADS = 15;

  /** What the outcome page shows: no personal data. {@code nextReload} is null for no reload. */
  public record Outcome(
      String number, OrderStatus status, long totalCents, String sessionId, Integer nextReload) {
    public String total() {
      return Prices.format(totalCents);
    }

    public boolean pending() {
      return status == OrderStatus.PENDING_PAYMENT;
    }

    public boolean paid() {
      return status == OrderStatus.PAID;
    }

    public boolean expired() {
      return status == OrderStatus.EXPIRED;
    }

    public boolean reloads() {
      return nextReload != null;
    }
  }

  /** The order that was paid, or is paid, with this Checkout session; empty if not in this shop. */
  public Optional<CustomerOrder> bySessionId(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        CustomerOrder.<CustomerOrder>find("stripeCheckoutSessionId", sessionId).firstResult());
  }

  /**
   * The order with this {@code client_reference_id} (its id), locked until the end of the
   * transaction; empty if not in this shop. Locks the order only, never the cart.
   */
  public Optional<CustomerOrder> lockedByReference(String reference) {
    if (reference == null || !reference.matches("\\d{1,18}")) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        CustomerOrder.<CustomerOrder>find("id", Long.parseLong(reference))
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult());
  }

  /**
   * The outcome for the customer back from Stripe. {@code n} counts the reloads so far: anything
   * not a number in 0..14 counts as the last one.
   */
  public Optional<Outcome> outcome(String sessionId, String n) {
    return bySessionId(sessionId)
        .map(
            order -> {
              Integer next = null;
              if (order.status == OrderStatus.PENDING_PAYMENT && reloadsSoFar(n) < MAX_RELOADS) {
                next = reloadsSoFar(n) + 1;
              }
              return new Outcome(
                  order.number,
                  order.status,
                  order.totalCents,
                  order.stripeCheckoutSessionId,
                  next);
            });
  }

  private static int reloadsSoFar(String n) {
    if (n == null || !n.matches("\\d{1,2}")) {
      return n == null ? 0 : MAX_RELOADS;
    }
    return Math.min(Integer.parseInt(n), MAX_RELOADS);
  }
}
