package it.gerio.platform.tenant;

import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import io.quarkus.hibernate.orm.runtime.tenant.TenantResolver;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * Tells Hibernate which shop the current session belongs to (PROGETTO.md §5.1). Hibernate calls it
 * once, when it opens the session.
 *
 * <p>Deliberately does <b>not</b> override {@code isRoot}: a "root" tenant skips the discriminator
 * filter and sees the rows of every shop. Cross-shop work goes through native SQL in a dedicated,
 * audited service (§5.3), never through a root tenant.
 *
 * <p>Without an active request context Quarkus does not even call this class and hands Hibernate a
 * null tenant, which Hibernate rejects as soon as a {@code @TenantId} entity exists (verified in
 * the bytecode, docs/verifiche.md). Jobs must use {@code @ActivateRequestContext} and set the
 * {@link TenantContext}.
 */
@PersistenceUnitExtension
@RequestScoped
public class PlatformTenantResolver implements TenantResolver {

  @Inject TenantContext context;

  /**
   * Used by Quarkus only for the DATABASE and SCHEMA strategies, never for DISCRIMINATOR (verified
   * in the bytecode). Refused rather than returning a fallback tenant.
   */
  @Override
  public String getDefaultTenantId() {
    throw new UnsupportedOperationException("No default tenant: the tenant is always explicit");
  }

  @Override
  public String resolveTenantId() {
    return context.require().id();
  }
}
