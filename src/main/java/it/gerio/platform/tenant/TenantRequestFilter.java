package it.gerio.platform.tenant;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.runtime.BlockingOperationControl;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * Decides the tenant of every request from its {@code Host} (PROGETTO.md §5.2, ADR-0004), before
 * any endpoint runs and before any Hibernate session opens.
 *
 * <p>Order, and why:
 *
 * <ol>
 *   <li>missing or invalid host → 404;
 *   <li>the context becomes the platform, for the lookups below;
 *   <li>the base domain → stays the platform (checked before any shop, so no shop can take it);
 *   <li>{@code slug.<base>} → shop by slug; deeper names below the base → 404;
 *   <li>any other host → shop by verified custom domain;
 *   <li>no shop → 404; shop not ACTIVE → 503, before any redirect;
 *   <li>not the shop's primary host → redirect there (301 for GET/HEAD, 308 otherwise so a POST
 *       stays a POST), cached for one hour only: a 301 without {@code Cache-Control} stays in the
 *       browser forever;
 *   <li>switch the {@link TenantContext} to the shop (the one platform → shop switch allowed).
 * </ol>
 *
 * <p>The host is never taken from {@code X-Forwarded-Host}: Caddy forwards the original {@code
 * Host}, and {@code enable-forwarded-host} stays off (ADR-0004).
 *
 * <p>Threading: a filter runs on the thread of the endpoint it guards. Shop endpoints must be
 * blocking (the default for methods that don't return {@code Uni}), because this filter queries the
 * database. Pre-matching filters always run on the event loop, hence this one is not pre-matching.
 */
public class TenantRequestFilter {

  private static final String REDIRECT_CACHE = "max-age=3600";

  @Inject TenantContext tenantContext;
  @Inject TenantDirectory directory;
  @Inject PlatformDomain platform;

  @Location("errors/not-found.html")
  Template notFound;

  @Location("errors/unavailable.html")
  Template unavailable;

  @ServerRequestFilter
  public Optional<Response> resolveTenant(HttpServerRequest request) {
    // Fail on every host, the platform's too: otherwise a non-blocking endpoint
    // would work in dev on localhost and break only on shop hosts, in production.
    if (!BlockingOperationControl.isBlockingAllowed()) {
      throw new IllegalStateException(
          "TenantRequestFilter queries the database: this endpoint must be blocking,"
              + " not return Uni (ADR-0004 point 11)");
    }
    HostAndPort authority = request.authority();
    Optional<String> host =
        authority == null ? Optional.empty() : HostNormalizer.normalize(authority.host());
    if (host.isEmpty()) {
      return Optional.of(notFound());
    }
    String hostname = host.get();

    // The request is the platform's until a shop is found. Hibernate asks for the
    // tenant even to build TenantDirectory's own session, so the lookup needs one;
    // the request's session is not open yet, so switchTo below is safe.
    tenantContext.set(CurrentTenant.PLATFORM);
    if (platform.isPlatform(hostname)) {
      return Optional.empty();
    }

    Optional<ResolvedShop> found =
        platform.isUnderBase(hostname)
            ? platform.slugOf(hostname).flatMap(directory::findBySlug)
            : directory.findByHostname(hostname);
    if (found.isEmpty()) {
      return Optional.of(notFound());
    }
    ResolvedShop shop = found.get();

    if (shop.status() != TenantStatus.ACTIVE) {
      return Optional.of(unavailable());
    }
    // "www.x" and "x" normalize to the same host: no redirect between them, the
    // shop may have pointed only the "www" name to us (§5.4).
    if (!hostname.equals(shop.primaryHost())) {
      // A request-target without the leading "/" (e.g. "@evil.com") would turn
      // the Location into https://primary@evil.com. Browsers never send one.
      if (!request.path().startsWith("/")) {
        return Optional.of(notFound());
      }
      return Optional.of(redirect(request, shop.primaryHost()));
    }

    tenantContext.switchTo(shop.toCurrentTenant());
    return Optional.empty();
  }

  private Response redirect(HttpServerRequest request, String primaryHost) {
    boolean safeMethod = request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD;
    // The host always comes from the database, never from the request: no open
    // redirect. Path and query stay exactly as received (still percent-encoded).
    StringBuilder location = new StringBuilder(request.scheme()).append("://").append(primaryHost);
    platform.redirectPort().ifPresent(port -> location.append(':').append(port));
    location.append(request.path());
    if (request.query() != null) {
      location.append('?').append(request.query());
    }
    return Response.status(safeMethod ? 301 : 308)
        .header(HttpHeaders.LOCATION, location.toString())
        .header(HttpHeaders.CACHE_CONTROL, REDIRECT_CACHE)
        .build();
  }

  // Generic pages: they never name a shop, so they reveal nothing about which
  // shops exist.
  private Response notFound() {
    return Response.status(404).type(MediaType.TEXT_HTML).entity(notFound.render()).build();
  }

  private Response unavailable() {
    return Response.status(503).type(MediaType.TEXT_HTML).entity(unavailable.render()).build();
  }
}
