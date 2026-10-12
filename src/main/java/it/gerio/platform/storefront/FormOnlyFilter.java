package it.gerio.platform.storefront;

import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import java.util.Set;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * On a shop host, a request that changes something must be a form (giro 011). The CSRF check skips
 * the token for requests that are not forms (needed for Stripe's JSON webhook, which lives on the
 * platform domain), so without this a {@code fetch} with no {@code Content-Type} would reach an
 * endpoint without a token. {@code @Consumes} alone does not cover a missing {@code Content-Type}.
 * Runs after {@code TenantRequestFilter}. The webhook path is left to its own resource (404 on a
 * shop host).
 */
public class FormOnlyFilter {

  private static final Set<String> CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final int AFTER_TENANT_FILTER = 5010;

  @Inject TenantContext tenantContext;

  @ServerRequestFilter(priority = AFTER_TENANT_FILTER)
  public Optional<Response> onlyForms(ContainerRequestContext request) {
    if (!(tenantContext.current().orElse(null) instanceof CurrentTenant.Shop)
        || !CHANGING.contains(request.getMethod())
        || request.getUriInfo().getPath().startsWith("/webhooks/")) {
      return Optional.empty();
    }
    MediaType type = request.getMediaType();
    boolean form =
        type != null
            && (type.isCompatible(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                || type.isCompatible(MediaType.MULTIPART_FORM_DATA_TYPE));
    return form ? Optional.empty() : Optional.of(Response.status(415).build());
  }
}
