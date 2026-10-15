package it.gerio.platform.order;

import io.quarkus.panache.common.Sort;
import it.gerio.platform.catalog.Prices;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.ShopProfile;
import it.gerio.platform.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds orders for the Stripe webhook and for the page where the customer comes back. Hibernate
 * limits every query to the shop of the request, so an id of another shop is simply not found.
 */
@ApplicationScoped
public class OrderLookup {

  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  @Inject TenantContext tenantContext;
  @Inject ShopProfile shopProfile;

  /** One line of the status page, amounts already formatted. */
  public record StatusLine(String name, int quantity, String unitPrice, String lineTotal) {}

  /** What the status page shows: no personal data of the customer. */
  public record Status(
      String number,
      String statusLabel,
      boolean expired,
      String orderDate,
      String pickupDate,
      List<StatusLine> lines,
      String total,
      String shopPhone,
      String shopEmail) {}

  /** The page reloads itself every 2 seconds up to this many times (30 seconds). */
  static final int MAX_RELOADS = 15;

  /** What the outcome page shows: no personal data. {@code nextReload} is null for no reload. */
  public record Outcome(
      String number,
      OrderStatus status,
      long totalCents,
      String sessionId,
      Integer nextReload,
      String token) {
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
                  next,
                  order.publicToken);
            });
  }

  /**
   * The status page of the order with this secret token; empty if malformed or not in this shop.
   */
  public Optional<Status> status(String token) {
    if (token == null
        || !TOKEN.matcher(token).matches()
        || !(tenantContext.require() instanceof CurrentTenant.Shop shop)) {
      return Optional.empty();
    }
    CustomerOrder order = CustomerOrder.<CustomerOrder>find("publicToken", token).firstResult();
    if (order == null) {
      return Optional.empty();
    }
    List<StatusLine> lines =
        OrderItem.<OrderItem>find("orderId", Sort.by("id"), order.getId()).stream()
            .map(
                item ->
                    new StatusLine(
                        item.nameSnapshot,
                        item.quantity,
                        Prices.format(item.unitPriceCents),
                        Prices.format(item.lineTotalCents)))
            .toList();
    Optional<ShopProfile.Info> info = shopProfile.current();
    return Optional.of(
        new Status(
            order.number,
            order.status.label(),
            order.status == OrderStatus.EXPIRED,
            DATE.format(order.getCreatedAt().atZone(shop.timezone())),
            DATE.format(order.deliveryDate),
            lines,
            Prices.format(order.totalCents),
            info.map(ShopProfile.Info::phone).orElse(null),
            info.map(ShopProfile.Info::email).orElse(null)));
  }

  private static int reloadsSoFar(String n) {
    if (n == null || !n.matches("\\d{1,2}")) {
      return n == null ? 0 : MAX_RELOADS;
    }
    return Math.min(Integer.parseInt(n), MAX_RELOADS);
  }
}
