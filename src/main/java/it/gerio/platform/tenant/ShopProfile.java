package it.gerio.platform.tenant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * What the current shop shows to its customers and what Stripe needs to know about it. Reads only
 * the shop of the request, found through the {@link TenantContext}, never by an id from the request
 * ({@link Tenant} is not filtered by shop).
 */
@ApplicationScoped
public class ShopProfile {

  /** Contacts and the Stripe connected account; {@code stripeAccountId} is null until connected. */
  public record Info(
      String phone, String email, String stripeAccountId, boolean stripeChargesEnabled) {

    /** The payment can be started only on a connected account that can receive charges. */
    public boolean canCharge() {
      return stripeAccountId != null && stripeChargesEnabled;
    }
  }

  @Inject TenantContext tenantContext;

  public Optional<Info> current() {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop shop)) {
      return Optional.empty();
    }
    Tenant tenant = Tenant.findById(shop.id());
    if (tenant == null) {
      return Optional.empty();
    }
    return Optional.of(
        new Info(tenant.phone, tenant.email, tenant.stripeAccountId, tenant.stripeChargesEnabled));
  }
}
