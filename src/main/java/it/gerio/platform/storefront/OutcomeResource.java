package it.gerio.platform.storefront;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import it.gerio.platform.order.OrderLookup;
import it.gerio.platform.order.OrderLookup.Outcome;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Optional;

/**
 * Where Stripe sends the customer back (SPEC 7.5). Only on a shop host. The session id in the URL
 * is the only key, so the page shows number, status and total and nothing personal.
 */
@Path("/ordine/esito")
@Produces(MediaType.TEXT_HTML)
public class OutcomeResource {

  @CheckedTemplate(basePath = "storefront")
  static class Templates {
    static native TemplateInstance outcome(String shopName, Outcome outcome);
  }

  @Inject TenantContext tenantContext;
  @Inject OrderLookup orders;

  @Location("errors/not-found.html")
  Template notFound;

  @GET
  public Response outcome(@QueryParam("session_id") String sessionId, @QueryParam("n") String n) {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop shop)) {
      return notFound();
    }
    Optional<Outcome> outcome = orders.outcome(sessionId, n);
    if (outcome.isEmpty()) {
      return notFound();
    }
    return Response.ok(Templates.outcome(shop.displayName(), outcome.get()).render())
        .header("Cache-Control", "no-store")
        .build();
  }

  private Response notFound() {
    return Response.status(404).entity(notFound.render()).build();
  }
}
