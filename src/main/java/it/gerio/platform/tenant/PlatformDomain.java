package it.gerio.platform.tenant;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Rules about the platform's base domain (ADR-0004): which hosts are the platform itself, which are
 * a shop's free subdomain {@code slug.<base>}, and how a subdomain is built from a slug.
 *
 * <p>{@code @Startup}: created when the application starts, so a base domain in the wrong form
 * (e.g. {@code Piattaforma.it.}) stops the start instead of turning every request into a 404.
 */
@Startup
@ApplicationScoped
public class PlatformDomain {

  private final String base;
  private final OptionalInt redirectPort;

  @Inject
  public PlatformDomain(PlatformConfig config) {
    this(config.baseDomain(), config.redirectPort());
  }

  PlatformDomain(String base, OptionalInt redirectPort) {
    Objects.requireNonNull(base, "base");
    if (!HostNormalizer.normalize(base).equals(Optional.of(base))) {
      throw new IllegalArgumentException(
          "app.platform.base-domain must be a normalized hostname (lowercase, no port, no"
              + " trailing dot, no www.), got: '"
              + base
              + "'");
    }
    this.base = base;
    this.redirectPort = Objects.requireNonNull(redirectPort, "redirectPort");
  }

  public String base() {
    return base;
  }

  public OptionalInt redirectPort() {
    return redirectPort;
  }

  /** The platform itself (landing, admin area, webhooks). Exact match, host already normalized. */
  public boolean isPlatform(String host) {
    return host.equals(base);
  }

  /**
   * Any host below the base domain. Such hosts are resolved only by slug, never through {@code
   * tenant_domain}. Compares with {@code "." + base}: {@code evilpiattaforma.it} is not below
   * {@code piattaforma.it}.
   */
  public boolean isUnderBase(String host) {
    return host.endsWith("." + base);
  }

  /** The slug of {@code slug.<base>}; empty for any other host, including {@code a.b.<base>}. */
  public Optional<String> slugOf(String host) {
    if (!isUnderBase(host)) {
      return Optional.empty();
    }
    String label = host.substring(0, host.length() - base.length() - 1);
    return label.contains(".") ? Optional.empty() : Optional.of(label);
  }

  /** The free subdomain of a shop. */
  public String subdomainOf(String slug) {
    return slug + "." + base;
  }
}
