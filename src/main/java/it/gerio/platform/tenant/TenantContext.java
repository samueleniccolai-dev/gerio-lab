package it.gerio.platform.tenant;

import jakarta.enterprise.context.RequestScoped;
import java.util.Objects;
import java.util.Optional;

/**
 * The tenant of the current request (PROGETTO.md §5.2). Set by {@code TenantRequestFilter}, or
 * explicitly by webhooks and jobs, <b>before</b> the request's Hibernate session is opened:
 * Hibernate reads the tenant when it opens the session and never again.
 */
@RequestScoped
public class TenantContext {

  private CurrentTenant current;

  /** Sets the tenant for this request. A second call is a bug and fails. */
  public void set(CurrentTenant tenant) {
    Objects.requireNonNull(tenant, "tenant");
    if (current != null) {
      throw new IllegalStateException("Tenant already set for this request: " + current.id());
    }
    current = tenant;
  }

  /**
   * From the platform to a shop, once. Two uses: the tenant filter, which runs its host lookups as
   * the platform and then switches to the shop it found; and the Stripe webhook (§5.2), which
   * stores the event as the platform and then works for the shop named in the event.
   *
   * <p>A Hibernate session already open keeps the platform tenant: do the shop work in a new
   * transaction ({@code @Transactional(REQUIRES_NEW)} on another bean). Switching from shop A to
   * shop B is refused: writes in A's open session would silently go to A.
   */
  public void switchTo(CurrentTenant.Shop shop) {
    Objects.requireNonNull(shop, "shop");
    if (!(current instanceof CurrentTenant.Platform)) {
      throw new IllegalStateException(
          "switchTo is allowed only from the platform context, current: "
              + (current == null ? "none" : current.id()));
    }
    current = shop;
  }

  /** The tenant, or an exception: never proceed with an undefined shop. */
  public CurrentTenant require() {
    if (current == null) {
      throw new IllegalStateException(
          "No tenant in context: set TenantContext before using the database (PROGETTO.md §5.2)");
    }
    return current;
  }

  public Optional<CurrentTenant> current() {
    return Optional.ofNullable(current);
  }
}
