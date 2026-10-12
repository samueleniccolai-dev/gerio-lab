package it.gerio.platform.tenant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;

/**
 * Finds the shop behind a host (PROGETTO.md §5.2), before the request has a tenant.
 *
 * <p>Uses its own short {@link StatelessSession} with the platform tenant set explicitly, never the
 * request's session, which would stay fixed to the platform for the rest of the request (Hibernate
 * reads the tenant once, when the session opens). The caller must be in the platform context
 * anyway: Hibernate 7.2 calls {@link PlatformTenantResolver} already when it builds the stateless
 * session, before the explicit tenant replaces that value.
 *
 * <p>Only <b>verified</b> custom domains count, and never a {@code tenant_domain} row below the
 * base domain: those hosts belong to the slugs. Otherwise a row {@code b.<base>} marked primary on
 * shop A would redirect A's customers to shop B, and an unverified domain would redirect them to a
 * host that doesn't answer yet.
 *
 * <p>One query per request for now; the in-memory cache of §5.2 comes later (ADR-0004).
 */
@ApplicationScoped
public class TenantDirectory {

  @Inject SessionFactory sessionFactory;
  @Inject PlatformDomain platform;

  public Optional<ResolvedShop> findBySlug(String slug) {
    return withPlatformSession(
        session ->
            session
                .createSelectionQuery("from Tenant where slug = :slug", Tenant.class)
                .setParameter("slug", slug)
                .uniqueResultOptional()
                .map(tenant -> resolve(session, tenant)));
  }

  /**
   * The shop connected to this Stripe account, whatever its state (a suspended shop was still
   * paid). Used by the webhook, which runs as the platform.
   */
  public Optional<ResolvedShop> findByStripeAccount(String accountId) {
    if (accountId == null || accountId.isBlank()) {
      return Optional.empty();
    }
    return withPlatformSession(
        session ->
            session
                .createSelectionQuery("from Tenant where stripeAccountId = :account", Tenant.class)
                .setParameter("account", accountId)
                .uniqueResultOptional()
                .map(tenant -> resolve(session, tenant)));
  }

  /** {@code hostname} must already be normalized ({@link HostNormalizer}). */
  public Optional<ResolvedShop> findByHostname(String hostname) {
    if (platform.isPlatform(hostname) || platform.isUnderBase(hostname)) {
      return Optional.empty();
    }
    return withPlatformSession(
        session ->
            session
                .createSelectionQuery(
                    "select t from Tenant t, TenantDomain d"
                        + " where d.tenantId = t.id and d.hostname = :hostname"
                        + " and d.verifiedAt is not null",
                    Tenant.class)
                .setParameter("hostname", hostname)
                .uniqueResultOptional()
                .map(tenant -> resolve(session, tenant)));
  }

  private ResolvedShop resolve(StatelessSession session, Tenant tenant) {
    List<String> primaryDomains =
        session
            .createSelectionQuery(
                "select hostname from TenantDomain"
                    + " where tenantId = :tenantId and primary = true and verifiedAt is not null",
                String.class)
            .setParameter("tenantId", tenant.getId())
            .getResultList();
    String primaryHost =
        primaryDomains.stream()
            .filter(host -> !platform.isPlatform(host) && !platform.isUnderBase(host))
            .findFirst()
            .orElseGet(() -> platform.subdomainOf(tenant.slug));
    return new ResolvedShop(
        tenant.getId(), tenant.displayName, ZoneId.of(tenant.timezone), tenant.status, primaryHost);
  }

  private <T> T withPlatformSession(Function<StatelessSession, T> work) {
    try (StatelessSession session =
        sessionFactory
            .withStatelessOptions()
            .tenantIdentifier((Object) CurrentTenant.PLATFORM_ID)
            .openStatelessSession()) {
      return work.apply(session);
    }
  }
}
