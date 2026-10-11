package it.gerio.platform.cart;

import it.gerio.platform.catalog.CatalogService;
import it.gerio.platform.catalog.CatalogService.SellableVariant;
import it.gerio.platform.catalog.Prices;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The cart of a shop. Every query is limited to the request's shop by Hibernate, so a token or a
 * row id of another shop is simply not found. A cart is found by its token; an unknown token is
 * ignored (empty cart; a new cart with a new token on the first add).
 */
@ApplicationScoped
public class CartService {

  public static final int MAX_QUANTITY = 20;

  private static final SecureRandom RANDOM = new SecureRandom();

  @Inject CatalogService catalog;

  /** One line of the cart page. Amounts in cents. */
  public record Line(
      long itemId,
      String productName,
      String productSlug,
      String variantName,
      long unitCents,
      int quantity,
      long subtotalCents) {
    public String unitPrice() {
      return Prices.format(unitCents);
    }

    public String subtotal() {
      return Prices.format(subtotalCents);
    }
  }

  public record CartView(List<Line> lines, long totalCents) {
    public String total() {
      return Prices.format(totalCents);
    }
  }

  public enum Outcome {
    DONE,
    NOT_FOUND,
    BAD_REQUEST
  }

  /** Result of a change: the outcome and, when DONE, the token of the cart to put in the cookie. */
  public record Result(Outcome outcome, String token) {
    static Result of(Outcome outcome) {
      return new Result(outcome, null);
    }
  }

  public CartView view(String token) {
    Cart cart = find(token, false);
    if (cart == null) {
      return new CartView(List.of(), 0);
    }
    List<CartItem> items = CartItem.list("cartId = ?1 order by id", cart.getId());
    Map<Long, SellableVariant> variants =
        catalog.sellableVariants(items.stream().map(item -> item.variantId).toList());
    List<Line> lines =
        items.stream()
            .filter(item -> variants.containsKey(item.variantId))
            .map(
                item -> {
                  SellableVariant v = variants.get(item.variantId);
                  return new Line(
                      item.getId(),
                      v.productName(),
                      v.productSlug(),
                      v.variantName(),
                      v.priceCents(),
                      item.quantity,
                      v.priceCents() * item.quantity);
                })
            .toList();
    return new CartView(lines, lines.stream().mapToLong(Line::subtotalCents).sum());
  }

  /** Adds {@code quantity} (1..20) of a variant; the total per variant stops at 20. */
  @Transactional
  public Result add(String token, String variantId, String quantity) {
    OptionalInt n = quantity(quantity, 1);
    OptionalLong id = id(variantId);
    if (n.isEmpty() || id.isEmpty()) {
      return Result.of(Outcome.BAD_REQUEST);
    }
    if (catalog.sellableVariant(id.getAsLong()).isEmpty()) {
      return Result.of(Outcome.NOT_FOUND);
    }
    Cart cart = find(token, true);
    if (cart == null) {
      cart = new Cart();
      cart.token = newToken();
      cart.persist();
    }
    CartItem item =
        CartItem.find("cartId = ?1 and variantId = ?2", cart.getId(), id.getAsLong()).firstResult();
    if (item == null) {
      item = new CartItem();
      item.cartId = cart.getId();
      item.variantId = id.getAsLong();
      item.quantity = n.getAsInt();
      item.persist();
    } else {
      item.quantity = Math.min(item.quantity + n.getAsInt(), MAX_QUANTITY);
    }
    return new Result(Outcome.DONE, cart.token);
  }

  /** Sets the quantity of a row (0..20); 0 removes it. */
  @Transactional
  public Result setQuantity(String token, long itemId, String quantity) {
    OptionalInt n = quantity(quantity, 0);
    if (n.isEmpty()) {
      return Result.of(Outcome.BAD_REQUEST);
    }
    Optional<CartItem> item = ownItem(token, itemId);
    if (item.isEmpty()) {
      return Result.of(Outcome.NOT_FOUND);
    }
    if (n.getAsInt() == 0) {
      item.get().delete();
    } else {
      item.get().quantity = n.getAsInt();
    }
    return new Result(Outcome.DONE, token);
  }

  @Transactional
  public Result remove(String token, long itemId) {
    Optional<CartItem> item = ownItem(token, itemId);
    if (item.isEmpty()) {
      return Result.of(Outcome.NOT_FOUND);
    }
    item.get().delete();
    return new Result(Outcome.DONE, token);
  }

  /** A row of the cart with this token (and of this shop); locks the cart to serialize changes. */
  private Optional<CartItem> ownItem(String token, long itemId) {
    Cart cart = find(token, true);
    if (cart == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        CartItem.<CartItem>find("id = ?1 and cartId = ?2", itemId, cart.getId()).firstResult());
  }

  private static Cart find(String token, boolean lock) {
    if (token == null || token.isBlank()) {
      return null;
    }
    var query = Cart.<Cart>find("token", token);
    return (lock ? query.withLock(LockModeType.PESSIMISTIC_WRITE) : query).firstResult();
  }

  /** A quantity in {@code min..20}, or empty. */
  private static OptionalInt quantity(String text, int min) {
    if (text == null || !text.matches("\\d{1,9}")) {
      return OptionalInt.empty();
    }
    int value = Integer.parseInt(text);
    return value < min || value > MAX_QUANTITY ? OptionalInt.empty() : OptionalInt.of(value);
  }

  private static OptionalLong id(String text) {
    return text != null && text.matches("\\d{1,18}")
        ? OptionalLong.of(Long.parseLong(text))
        : OptionalLong.empty();
  }

  private static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
