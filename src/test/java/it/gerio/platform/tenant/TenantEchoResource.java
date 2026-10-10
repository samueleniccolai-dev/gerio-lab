package it.gerio.platform.tenant;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Test-only endpoint: answers with the tenant id the filter put in the context, for any path below
 * it. Lets the filter tests check the outcome without depending on real pages.
 */
@Path("/test/tenant/{rest: .*}")
@Produces(MediaType.TEXT_PLAIN)
public class TenantEchoResource {

  @Inject TenantContext tenantContext;

  @GET
  public String get() {
    return tenantContext.require().id();
  }

  @POST
  public String post() {
    return tenantContext.require().id();
  }
}
