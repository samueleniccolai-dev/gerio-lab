package it.gerio.platform.storefront;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Home page: the shop's page on a shop host, the landing page on the platform domain. A probe page
 * for R0: it proves that A's host shows A and never B.
 *
 * <p>Reads only the {@link TenantContext} set by the tenant filter, never the tenant entities
 * (PROGETTO.md §4.3). Blocking method (it doesn't return {@code Uni}), as the tenant filter
 * requires.
 */
@Path("/")
public class HomeResource {

  @CheckedTemplate(basePath = "storefront")
  static class StorefrontTemplates {
    static native TemplateInstance home(String shopName);
  }

  @CheckedTemplate(basePath = "platform")
  static class PlatformTemplates {
    static native TemplateInstance landing();
  }

  @Inject TenantContext tenantContext;

  @GET
  @Produces(MediaType.TEXT_HTML)
  public TemplateInstance home() {
    return switch (tenantContext.require()) {
      case CurrentTenant.Shop shop -> StorefrontTemplates.home(shop.displayName());
      case CurrentTenant.Platform platform -> PlatformTemplates.landing();
    };
  }
}
