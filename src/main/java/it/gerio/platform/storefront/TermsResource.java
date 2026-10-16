package it.gerio.platform.storefront;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import it.gerio.platform.legal.TermsDocument;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** The terms of sale of the shop of the host, as a page and as a PDF. Only on a shop host. */
@Path("/")
public class TermsResource {

  @Location("errors/not-found.html")
  Template notFound;

  @Inject TenantContext tenantContext;
  @Inject TermsDocument terms;

  @GET
  @Path("condizioni-di-vendita")
  public Response page() {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop)) {
      return notFound();
    }
    return Response.ok(terms.html(TermsDocument.CURRENT_VERSION), MediaType.TEXT_HTML).build();
  }

  @GET
  @Path("condizioni-di-vendita.pdf")
  public Response pdf() {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop)) {
      return notFound();
    }
    String version = TermsDocument.CURRENT_VERSION;
    return Response.ok(terms.pdf(version), "application/pdf")
        .header(
            "Content-Disposition", "inline; filename=\"" + TermsDocument.fileName(version) + "\"")
        .build();
  }

  private Response notFound() {
    return Response.status(404).type(MediaType.TEXT_HTML).entity(notFound.render()).build();
  }
}
