package it.gerio.platform.storefront;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import it.gerio.platform.order.CheckoutService;
import it.gerio.platform.order.CheckoutService.Form;
import it.gerio.platform.order.CheckoutService.Page;
import it.gerio.platform.order.CheckoutService.Result;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.PlatformDomain;
import it.gerio.platform.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * Summary and "Ordina e paga" of a shop. Only on a shop host: on the platform domain both routes
 * are 404. The rules live in {@link CheckoutService}; this class maps outcomes to HTTP.
 */
@Path("/checkout")
@Produces(MediaType.TEXT_HTML)
public class CheckoutResource {

  static final String PAYMENT_ERROR = "Non è stato possibile avviare il pagamento. Riprova.";

  @CheckedTemplate(basePath = "storefront")
  static class Templates {
    static native TemplateInstance checkout(
        String shopName, Page page, Form form, Map<String, String> errors, String error);
  }

  @Inject TenantContext tenantContext;
  @Inject CheckoutService checkoutService;
  @Inject PlatformDomain platform;

  @Location("errors/not-found.html")
  Template notFound;

  @GET
  public Response summary(@CookieParam(CartResource.COOKIE) String token) {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop shop)) {
      return notFound();
    }
    Optional<Page> page = checkoutService.page(token);
    if (page.isEmpty()) {
      return toCart();
    }
    return Response.ok(render(shop, page.get(), Form.empty(), Map.of(), null)).build();
  }

  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response order(
      @CookieParam(CartResource.COOKIE) String token,
      @Context UriInfo uriInfo,
      @FormParam("customerName") String name,
      @FormParam("customerEmail") String email,
      @FormParam("customerPhone") String phone,
      @FormParam("pickupDate") String pickupDate,
      @FormParam("fulfillmentType") String fulfillmentType,
      @FormParam("recipientName") String recipientName,
      @FormParam("recipientPhone") String recipientPhone,
      @FormParam("addressLine") String addressLine,
      @FormParam("postalCode") String postalCode,
      @FormParam("city") String city,
      @FormParam("deliveryNotes") String deliveryNotes) {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop shop)) {
      return notFound();
    }
    // No type means a pickup (forms from before the delivery). Zone and fee are never read here.
    String type = fulfillmentType == null || fulfillmentType.isBlank() ? "PICKUP" : fulfillmentType;
    Form form =
        new Form(
            name,
            email,
            phone,
            pickupDate,
            type,
            recipientName,
            recipientPhone,
            addressLine,
            postalCode,
            city,
            deliveryNotes);
    Result result = checkoutService.checkout(token, baseUrl(uriInfo), form);
    return switch (result) {
      case Result.Redirect redirect -> Response.seeOther(URI.create(redirect.url())).build();
      case Result.EmptyCart empty -> toCart();
      case Result.Invalid invalid -> again(shop, token, 400, form, invalid.errors(), null);
      case Result.Disabled disabled -> again(shop, token, 409, form, Map.of(), null);
      case Result.PaymentFailed failed -> again(shop, token, 502, form, Map.of(), PAYMENT_ERROR);
    };
  }

  private Response again(
      CurrentTenant.Shop shop,
      String token,
      int status,
      Form form,
      Map<String, String> errors,
      String error) {
    Optional<Page> page = checkoutService.page(token);
    if (page.isEmpty()) {
      return toCart();
    }
    return Response.status(status).entity(render(shop, page.get(), form, errors, error)).build();
  }

  private static String render(
      CurrentTenant.Shop shop, Page page, Form form, Map<String, String> errors, String error) {
    Form shown =
        new Form(
            nullToEmpty(form.name()),
            nullToEmpty(form.email()),
            nullToEmpty(form.phone()),
            nullToEmpty(form.pickupDate()),
            form.fulfillmentType(),
            nullToEmpty(form.recipientName()),
            nullToEmpty(form.recipientPhone()),
            nullToEmpty(form.addressLine()),
            nullToEmpty(form.postalCode()),
            nullToEmpty(form.city()),
            nullToEmpty(form.deliveryNotes()));
    return Templates.checkout(shop.displayName(), page, shown, errors, error).render();
  }

  /**
   * The shop's own address for Stripe's return pages. The tenant filter lets a request through only
   * on the shop's primary host, so the host here is the shop's, not the caller's choice.
   */
  private String baseUrl(UriInfo uriInfo) {
    StringBuilder base = new StringBuilder("https://").append(uriInfo.getBaseUri().getHost());
    platform.redirectPort().ifPresent(port -> base.append(':').append(port));
    return base.toString();
  }

  private static String nullToEmpty(String text) {
    return text == null ? "" : text;
  }

  private static Response toCart() {
    return Response.seeOther(URI.create("/carrello")).build();
  }

  private Response notFound() {
    return Response.status(404).entity(notFound.render()).build();
  }
}
