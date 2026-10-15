package it.gerio.platform.storefront;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import it.gerio.platform.order.OrderLookup;
import it.gerio.platform.order.OrderLookup.Status;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Optional;

/**
 * The status page of an order, reached through its secret link (SPEC 7.2). Only on a shop host. Any
 * token that does not lead to an order of this shop gets the same 404 page.
 */
@Path("/ordine/{token}")
@Produces(MediaType.TEXT_HTML)
public class OrderStatusResource {

  @Location("storefront/order-status.html")
  Template page;

  @Inject TenantContext tenantContext;
  @Inject OrderLookup orders;

  @Location("errors/not-found.html")
  Template notFound;

  @GET
  public Response status(@PathParam("token") String token) {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop shop)) {
      return notFound();
    }
    Optional<Status> status = orders.status(token);
    if (status.isEmpty()) {
      return notFound();
    }
    return Response.ok(
            page.data("shopName", shop.displayName()).data("status", status.get()).render())
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .header("X-Robots-Tag", "noindex")
        .build();
  }

  private Response notFound() {
    return Response.status(404).entity(notFound.render()).build();
  }
}
