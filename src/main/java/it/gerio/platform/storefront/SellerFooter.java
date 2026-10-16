package it.gerio.platform.storefront;

import it.gerio.platform.tenant.ShopProfile;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * The seller's data for the footer of the storefront layout, read in templates as {@code
 * {inject:sellerFooter.info}}. Request scoped: never shared between shops.
 */
@Named("sellerFooter")
@RequestScoped
public class SellerFooter {

  @Inject ShopProfile shopProfile;

  public ShopProfile.Info info() {
    return shopProfile.current().orElse(null);
  }
}
