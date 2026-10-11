package it.gerio.platform.storefront;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import it.gerio.platform.cart.CartService;
import it.gerio.platform.cart.CartService.CartView;
import it.gerio.platform.cart.CartService.Result;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Cart pages and actions of a shop, with classic forms. Only on a shop host: on the platform domain
 * every route is 404. The rules (quantities, what can be sold, whose row it is) live in {@link
 * CartService}; this class only maps outcomes to HTTP and keeps the technical cookie.
 */
@Path("/carrello")
@Produces(MediaType.TEXT_HTML)
public class CartResource {

  static final String COOKIE = "cart";
  private static final int COOKIE_MAX_AGE = 7 * 24 * 60 * 60;

  @CheckedTemplate(basePath = "storefront")
  static class Templates {
    static native TemplateInstance cart(String shopName, CartView cart);
  }

  @Inject TenantContext tenantContext;
  @Inject CartService cartService;

  @ConfigProperty(name = "app.cart.secure-cookie")
  boolean secureCookie;

  @Location("errors/not-found.html")
  Template notFound;

  @GET
  public Response cart(@CookieParam(COOKIE) String token) {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop shop)) {
      return notFound();
    }
    return Response.ok(Templates.cart(shop.displayName(), cartService.view(token)).render())
        .build();
  }

  @POST
  @Path("aggiungi")
  public Response add(
      @CookieParam(COOKIE) String token,
      @FormParam("variantId") String variantId,
      @FormParam("quantity") String quantity) {
    return change(() -> cartService.add(token, variantId, quantity == null ? "1" : quantity));
  }

  @POST
  @Path("righe/{itemId}")
  public Response update(
      @CookieParam(COOKIE) String token,
      @PathParam("itemId") long itemId,
      @FormParam("quantity") String quantity) {
    return change(() -> cartService.setQuantity(token, itemId, quantity));
  }

  @POST
  @Path("righe/{itemId}/rimuovi")
  public Response remove(@CookieParam(COOKIE) String token, @PathParam("itemId") long itemId) {
    return change(() -> cartService.remove(token, itemId));
  }

  private Response change(Supplier<Result> action) {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop)) {
      return notFound();
    }
    Result result = action.get();
    return switch (result.outcome()) {
      case DONE ->
          Response.seeOther(URI.create("/carrello")).cookie(cookie(result.token())).build();
      case NOT_FOUND -> notFound();
      case BAD_REQUEST ->
          Response.status(400).type(MediaType.TEXT_PLAIN).entity("Richiesta non valida").build();
    };
  }

  /** Technical cookie (SPEC 12.3): same value, lifetime renewed at every successful change. */
  private NewCookie cookie(String token) {
    return new NewCookie.Builder(COOKIE)
        .value(token)
        .path("/")
        .maxAge(COOKIE_MAX_AGE)
        .httpOnly(true)
        .secure(secureCookie)
        .sameSite(NewCookie.SameSite.LAX)
        .build();
  }

  private Response notFound() {
    return Response.status(404).entity(notFound.render()).build();
  }
}
