package it.gerio.platform.storefront;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.ShopProfile;
import it.gerio.platform.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.function.BiFunction;

/** The minimal legal pages of a shop (SPEC 2.2, 12.3). Only on a shop host. */
@Path("/")
public class LegalPagesResource {

  @CheckedTemplate(basePath = "storefront/legal")
  static class Templates {
    static native TemplateInstance seller(String shopName, ShopProfile.Info seller);

    static native TemplateInstance privacy(String shopName, ShopProfile.Info seller);

    static native TemplateInstance cookies(String shopName, ShopProfile.Info seller);

    static native TemplateInstance accessibility(
        String shopName, ShopProfile.Info seller, String updated);
  }

  /** Date of the accessibility statement, to update when the pages change. */
  static final String ACCESSIBILITY_UPDATED = "16/10/2026";

  @Location("errors/not-found.html")
  Template notFound;

  @Inject TenantContext tenantContext;
  @Inject ShopProfile shopProfile;

  @GET
  @Path("informazioni-venditore")
  public Response seller() {
    return page((name, info) -> Templates.seller(name, info));
  }

  @GET
  @Path("privacy")
  public Response privacy() {
    return page((name, info) -> Templates.privacy(name, info));
  }

  @GET
  @Path("cookie")
  public Response cookies() {
    return page((name, info) -> Templates.cookies(name, info));
  }

  @GET
  @Path("accessibilita")
  public Response accessibility() {
    return page((name, info) -> Templates.accessibility(name, info, ACCESSIBILITY_UPDATED));
  }

  private Response page(BiFunction<String, ShopProfile.Info, TemplateInstance> template) {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop shop)) {
      return Response.status(404).type(MediaType.TEXT_HTML).entity(notFound.render()).build();
    }
    ShopProfile.Info info =
        shopProfile.current().orElseThrow(() -> new IllegalStateException("No shop profile"));
    return Response.ok(template.apply(shop.displayName(), info).render(), MediaType.TEXT_HTML)
        .build();
  }
}
