package it.gerio.platform.storefront;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import it.gerio.platform.catalog.CatalogService;
import it.gerio.platform.catalog.CatalogService.CategoryView;
import it.gerio.platform.catalog.CatalogService.ProductView;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Catalog, category and product pages of a shop. Only on a shop host: on the platform domain they
 * are 404. The shop comes from the {@link TenantContext}; Hibernate limits every read to it, so a
 * slug of another shop is simply not found. No business rules here: {@link CatalogService} decides
 * what is visible.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class CatalogResource {

  @CheckedTemplate(basePath = "storefront")
  static class Templates {
    static native TemplateInstance catalog(String shopName, List<CategoryView> categories);

    static native TemplateInstance category(String shopName, CategoryView category);

    static native TemplateInstance product(String shopName, ProductView product);
  }

  @Inject TenantContext tenantContext;
  @Inject CatalogService catalogService;

  @Location("errors/not-found.html")
  Template notFound;

  @GET
  @Path("catalogo")
  public Response catalog() {
    return shop()
        .map(shop -> ok(Templates.catalog(shop.displayName(), catalogService.catalog())))
        .orElseGet(this::notFound);
  }

  @GET
  @Path("catalogo/{slug}")
  public Response category(@PathParam("slug") String slug) {
    return page(slug, catalogService::category, Templates::category);
  }

  @GET
  @Path("prodotti/{slug}")
  public Response product(@PathParam("slug") String slug) {
    return page(slug, catalogService::product, Templates::product);
  }

  private <T> Response page(
      String slug,
      Function<String, Optional<T>> lookup,
      java.util.function.BiFunction<String, T, TemplateInstance> template) {
    Optional<CurrentTenant.Shop> shop = shop();
    if (shop.isEmpty()) {
      return notFound();
    }
    return lookup
        .apply(slug)
        .map(found -> ok(template.apply(shop.get().displayName(), found)))
        .orElseGet(this::notFound);
  }

  private Optional<CurrentTenant.Shop> shop() {
    return tenantContext.require() instanceof CurrentTenant.Shop shop
        ? Optional.of(shop)
        : Optional.empty();
  }

  private static Response ok(TemplateInstance page) {
    return Response.ok(page.render()).build();
  }

  private Response notFound() {
    return Response.status(404).entity(notFound.render()).build();
  }
}
