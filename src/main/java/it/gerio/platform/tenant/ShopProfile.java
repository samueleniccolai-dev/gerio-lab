package it.gerio.platform.tenant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Optional;

/**
 * What the current shop shows to its customers and what Stripe needs to know about it. Reads only
 * the shop of the request, found through the {@link TenantContext}, never by an id from the request
 * ({@link Tenant} is not filtered by shop).
 */
@ApplicationScoped
public class ShopProfile {

  /**
   * Contacts, legal data of the seller and the Stripe connected account; {@code stripeAccountId} is
   * null until connected.
   */
  public record Info(
      String phone,
      String email,
      String stripeAccountId,
      boolean stripeChargesEnabled,
      String legalName,
      String vatNumber,
      String addressLine,
      String postalCode,
      String city,
      String orderCutoff,
      String whatsapp) {

    /** The payment can be started only on a connected account that can receive charges. */
    public boolean canCharge() {
      return stripeAccountId != null && stripeChargesEnabled;
    }
  }

  @Inject TenantContext tenantContext;
  @Inject EntityManager entityManager;

  // Two keys of tenant.settings (jsonb), read by hand: the column is not mapped in Tenant.
  private String[] settings(String tenantId) {
    Object[] row =
        (Object[])
            entityManager
                .createNativeQuery(
                    "select settings->>'orderCutoff', settings->>'whatsapp'"
                        + " from tenant where id = ?1")
                .setParameter(1, tenantId)
                .getSingleResult();
    return new String[] {(String) row[0], (String) row[1]};
  }

  public Optional<Info> current() {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop shop)) {
      return Optional.empty();
    }
    Tenant tenant = Tenant.findById(shop.id());
    if (tenant == null) {
      return Optional.empty();
    }
    String[] settings = settings(shop.id());
    return Optional.of(
        new Info(
            tenant.phone,
            tenant.email,
            tenant.stripeAccountId,
            tenant.stripeChargesEnabled,
            tenant.legalName,
            tenant.vatNumber,
            tenant.addressLine,
            tenant.postalCode,
            tenant.city,
            settings[0],
            settings[1]));
  }
}
