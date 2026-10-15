package it.gerio.platform.order;

import io.quarkus.narayana.jta.QuarkusTransaction;
import it.gerio.platform.cart.CartService;
import it.gerio.platform.cart.CartService.CartView;
import it.gerio.platform.cart.CartService.Snapshot;
import it.gerio.platform.cart.CartService.SnapshotLine;
import it.gerio.platform.legal.TermsDocument;
import it.gerio.platform.payment.StripeCheckout;
import it.gerio.platform.payment.StripeCheckout.PaymentUnavailableException;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.ShopProfile;
import it.gerio.platform.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * From the cart to a Stripe Checkout session (SPEC 7.2, 8.3), in two short transactions with the
 * call to Stripe in between, so no database connection is held during the HTTP call.
 *
 * <ol>
 *   <li>Transaction A: the cart is locked (two clicks queue up), prices are read from the catalog,
 *       the form is checked, and the order is created or the last pending one of the same cart is
 *       reused, if cart and data are the same.
 *   <li>Stripe, with the idempotency key {@code order-<id>-attempt-<n>} and parameters that are the
 *       same on every retry of the attempt.
 *   <li>Transaction B: the session id and URL are saved. If Stripe failed, the attempt is marked
 *       failed (no expiry) and the next try uses {@code n + 1}.
 * </ol>
 */
@ApplicationScoped
public class CheckoutService {

  static final String FULFILLMENT_PICKUP = "PICKUP";
  // Stripe wants at least 30 minutes: 31 leaves room for the clock between us and them (§8.3).
  static final Duration SESSION_LIFETIME = Duration.ofMinutes(31);
  // The place stays held a little longer than the session (§7.4).
  static final Duration HOLD = Duration.ofMinutes(35);
  private static final int MAX_TEXT = 200;
  private static final Logger LOG = Logger.getLogger(CheckoutService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** What the customer typed. All strings may be null. */
  public record Form(String name, String email, String phone, String pickupDate) {}

  /** What the summary page shows. */
  public record Page(
      CartView cart, boolean canPay, String phone, String email, String minPickupDate) {}

  public sealed interface Result {
    /** Go to this address (Stripe Checkout). */
    record Redirect(String url) implements Result {}

    /** The data is not valid: field name to message. */
    record Invalid(Map<String, String> errors) implements Result {}

    /** Nothing to order. */
    record EmptyCart() implements Result {}

    /** No Stripe key, or the shop cannot receive charges yet. */
    record Disabled() implements Result {}

    /** Stripe did not answer; the order is kept for the next try. */
    record PaymentFailed() implements Result {}
  }

  @Inject Clock clock;
  @Inject TenantContext tenantContext;
  @Inject CartService cartService;
  @Inject ShopProfile shopProfile;
  @Inject OrderNumbers numbers;
  @Inject OrderStateMachine stateMachine;
  @Inject StripeCheckout stripe;

  /** The summary of the cart; empty if there is nothing to order. */
  public Optional<Page> page(String cartToken) {
    CartView cart = cartService.view(cartToken);
    if (cart.lines().isEmpty()) {
      return Optional.empty();
    }
    Optional<ShopProfile.Info> shop = shopProfile.current();
    boolean canPay = canPay(shop);
    // The days of preparation are checked when the form is sent, with the prices.
    LocalDate earliest = earliestPickup(0);
    return Optional.of(
        new Page(
            cart,
            canPay,
            shop.map(ShopProfile.Info::phone).orElse(null),
            shop.map(ShopProfile.Info::email).orElse(null),
            earliest.toString()));
  }

  public Result checkout(String cartToken, String baseUrl, Form form) {
    Optional<ShopProfile.Info> shop = shopProfile.current();
    if (!canPay(shop)) {
      return new Result.Disabled();
    }
    String accountId = shop.get().stripeAccountId();
    String tenantId = tenantContext.require().id();

    Attempt attempt =
        QuarkusTransaction.requiringNew().call(() -> prepare(cartToken, form, baseUrl));
    if (attempt.early() != null) {
      return attempt.early();
    }

    StripeCheckout.Request request = attempt.request(accountId, tenantId);
    try {
      StripeCheckout.Session session = stripe.create(request);
      QuarkusTransaction.requiringNew().run(() -> saveSession(attempt, session));
      return new Result.Redirect(session.url());
    } catch (PaymentUnavailableException e) {
      LOG.warnf("Order %d attempt %d: no Stripe session", attempt.orderId(), attempt.number());
      QuarkusTransaction.requiringNew().run(() -> markFailed(attempt));
      return new Result.PaymentFailed();
    }
  }

  // --- transaction A --------------------------------------------------------------------------

  /**
   * Everything Stripe needs for one attempt; or {@code early}, the answer that ends the request.
   */
  private record Attempt(
      Result early,
      long orderId,
      int number,
      String orderNumber,
      String customerEmail,
      Instant expiresAt,
      String successUrl,
      String cancelUrl,
      List<StripeCheckout.Line> lines) {

    static Attempt ends(Result early) {
      return new Attempt(early, 0, 0, null, null, null, null, null, List.of());
    }

    StripeCheckout.Request request(String accountId, String tenantId) {
      return new StripeCheckout.Request(
          accountId,
          "order-" + orderId + "-attempt-" + number,
          Long.toString(orderId),
          tenantId,
          orderNumber,
          customerEmail,
          expiresAt,
          successUrl,
          cancelUrl,
          lines);
    }
  }

  private Attempt prepare(String cartToken, Form form, String baseUrl) {
    Optional<Snapshot> found = cartService.lockedSnapshot(cartToken);
    if (found.isEmpty()) {
      return Attempt.ends(new Result.EmptyCart());
    }
    Snapshot cart = found.get();
    Instant now = clock.instant();
    Map<String, String> errors = new LinkedHashMap<>();
    LocalDate date = validate(form, cart, errors);
    if (!errors.isEmpty()) {
      return Attempt.ends(new Result.Invalid(errors));
    }
    String name = form.name().strip();
    String email = form.email().strip();
    String phone = blankToNull(form.phone());

    CustomerOrder order = lastPending(cart.cartId());
    List<OrderItem> items = List.of();
    if (order != null) {
      items = OrderItem.list("orderId = ?1 order by id", order.getId());
      if (!sameOrder(order, items, cart, name, email, phone, date)) {
        order = null;
      }
    }
    if (order == null) {
      order = create(cart, name, email, phone, date, now);
      items = OrderItem.list("orderId = ?1 order by id", order.getId());
    }

    if (order.checkoutUrl != null
        && order.checkoutExpiresAt != null
        && order.checkoutExpiresAt.isAfter(now)) {
      return Attempt.ends(new Result.Redirect(order.checkoutUrl));
    }
    // A new attempt only if there was none, the last one failed (no expiry) or it expired.
    if (order.checkoutAttempt == 0
        || order.checkoutExpiresAt == null
        || !order.checkoutExpiresAt.isAfter(now)) {
      order.checkoutAttempt += 1;
      order.checkoutExpiresAt = now.plus(SESSION_LIFETIME);
      order.checkoutUrl = null;
      order.stripeCheckoutSessionId = null;
      order.holdExpiresAt = now.plus(HOLD);
    }
    List<StripeCheckout.Line> lines = new ArrayList<>();
    for (OrderItem item : items) {
      lines.add(new StripeCheckout.Line(item.nameSnapshot, item.unitPriceCents, item.quantity));
    }
    return new Attempt(
        null,
        order.getId(),
        order.checkoutAttempt,
        order.number,
        order.customerEmail,
        order.checkoutExpiresAt,
        baseUrl + "/ordine/esito?session_id={CHECKOUT_SESSION_ID}",
        baseUrl + "/checkout",
        lines);
  }

  private CustomerOrder lastPending(long cartId) {
    return CustomerOrder.<CustomerOrder>find(
            "cartId = ?1 and status = ?2 order by id desc", cartId, OrderStatus.PENDING_PAYMENT)
        .withLock(LockModeType.PESSIMISTIC_WRITE)
        .firstResult();
  }

  private static boolean sameOrder(
      CustomerOrder order,
      List<OrderItem> items,
      Snapshot cart,
      String name,
      String email,
      String phone,
      LocalDate date) {
    if (!order.customerName.equals(name)
        || !order.customerEmail.equals(email)
        || !Objects.equals(order.customerPhone, phone)
        || !order.deliveryDate.equals(date)
        || items.size() != cart.lines().size()) {
      return false;
    }
    for (int i = 0; i < items.size(); i++) {
      OrderItem item = items.get(i);
      SnapshotLine line = cart.lines().get(i);
      if (!Objects.equals(item.variantId, line.variantId())
          || item.unitPriceCents != line.unitCents()
          || item.quantity != line.quantity()) {
        return false;
      }
    }
    return true;
  }

  private CustomerOrder create(
      Snapshot cart, String name, String email, String phone, LocalDate date, Instant now) {
    CurrentTenant.Shop shop = (CurrentTenant.Shop) tenantContext.require();
    int year = now.atZone(shop.timezone()).getYear();
    CustomerOrder order = new CustomerOrder();
    order.cartId = cart.cartId();
    order.number = numbers.next(shop.id(), year);
    order.publicToken = newToken();
    order.fulfillmentType = FULFILLMENT_PICKUP;
    order.customerName = name;
    order.customerEmail = email;
    order.customerPhone = phone;
    order.deliveryDate = date;
    long subtotal = 0;
    List<VatBreakdown.Line> vat = new ArrayList<>();
    for (SnapshotLine line : cart.lines()) {
      long total = line.unitCents() * line.quantity();
      subtotal += total;
      vat.add(new VatBreakdown.Line(line.vatRateBp(), total));
    }
    order.subtotalCents = subtotal;
    order.deliveryFeeCents = 0;
    order.totalCents = subtotal;
    order.vatBreakdown = VatBreakdown.toJson(vat);
    order.termsVersion = TermsDocument.CURRENT_VERSION;
    order.holdExpiresAt = now.plus(HOLD);
    stateMachine.start(order);
    for (SnapshotLine line : cart.lines()) {
      OrderItem item = new OrderItem();
      item.orderId = order.getId();
      item.variantId = line.variantId();
      item.nameSnapshot = line.productName() + " — " + line.variantName();
      item.unitPriceCents = line.unitCents();
      item.quantity = line.quantity();
      item.vatRateBp = line.vatRateBp();
      item.lineTotalCents = line.unitCents() * line.quantity();
      item.persist();
    }
    return order;
  }

  // --- transaction B --------------------------------------------------------------------------

  private void saveSession(Attempt attempt, StripeCheckout.Session session) {
    applySession(lockedOrder(attempt.orderId()), attempt.number(), session);
  }

  private void markFailed(Attempt attempt) {
    applyFailure(lockedOrder(attempt.orderId()), attempt.number());
  }

  // A webhook may have paid or expired the order while Stripe was answering: only an order still
  // waiting for payment takes the session of the attempt.
  static void applySession(CustomerOrder order, int attempt, StripeCheckout.Session session) {
    if (order != null
        && order.status == OrderStatus.PENDING_PAYMENT
        && order.checkoutAttempt == attempt) {
      order.stripeCheckoutSessionId = session.id();
      order.checkoutUrl = session.url();
    }
  }

  static void applyFailure(CustomerOrder order, int attempt) {
    if (order != null
        && order.status == OrderStatus.PENDING_PAYMENT
        && order.checkoutAttempt == attempt
        && order.checkoutUrl == null) {
      order.checkoutExpiresAt = null;
    }
  }

  private static CustomerOrder lockedOrder(long id) {
    return CustomerOrder.findById(id, LockModeType.PESSIMISTIC_WRITE);
  }

  // --- rules ----------------------------------------------------------------------------------

  private boolean canPay(Optional<ShopProfile.Info> shop) {
    return stripe.configured() && shop.map(ShopProfile.Info::canCharge).orElse(false);
  }

  /** Today in the shop's time zone plus the days of preparation. */
  private LocalDate earliestPickup(int leadTimeDays) {
    CurrentTenant.Shop shop = (CurrentTenant.Shop) tenantContext.require();
    return clock.instant().atZone(shop.timezone()).toLocalDate().plusDays(leadTimeDays);
  }

  private LocalDate validate(Form form, Snapshot cart, Map<String, String> errors) {
    if (isBlank(form.name()) || form.name().strip().length() > MAX_TEXT) {
      errors.put("customerName", "Scrivi il tuo nome.");
    }
    String email = form.email() == null ? "" : form.email().strip();
    if (!email.matches("[^@\\s]+@[^@\\s]+") || email.length() > MAX_TEXT) {
      errors.put("customerEmail", "Scrivi un indirizzo email valido.");
    }
    if (form.phone() != null && form.phone().strip().length() > 40) {
      errors.put("customerPhone", "Il numero di telefono è troppo lungo.");
    }
    int lead = cart.lines().stream().mapToInt(SnapshotLine::leadTimeDays).max().orElse(0);
    LocalDate earliest = earliestPickup(lead);
    LocalDate date = null;
    try {
      date = isBlank(form.pickupDate()) ? null : LocalDate.parse(form.pickupDate().strip());
    } catch (DateTimeParseException e) {
      date = null;
    }
    if (date == null || date.isBefore(earliest)) {
      errors.put("pickupDate", "Scegli un giorno di ritiro valido, dal " + earliest + ".");
    }
    return date;
  }

  private static boolean isBlank(String text) {
    return text == null || text.isBlank();
  }

  private static String blankToNull(String text) {
    return isBlank(text) ? null : text.strip();
  }

  private static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
