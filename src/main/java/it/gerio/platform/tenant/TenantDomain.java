package it.gerio.platform.tenant;

import it.gerio.platform.common.EntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A custom domain that leads to a shop (PROGETTO.md §5.4). Global table: the tenant filter looks it
 * up before knowing the shop, so it has a plain {@code tenant_id} column, not {@code @TenantId}.
 *
 * <p>The free subdomain {@code slug.<base-domain>} is not stored here: it is derived from the slug
 * (ADR-0004).
 *
 * <p><b>Not filtered by shop</b>, like {@link Tenant}: in a shop request, always add {@code
 * tenantId = tenantContext.require().id()} to the query, and test it with shops A and B.
 */
@Entity
@Table(name = "tenant_domain")
public class TenantDomain extends EntityBase {

  @Column(name = "tenant_id", nullable = false, updatable = false)
  public String tenantId;

  /** In normalized form (see the V1 check constraint {@code tenant_domain_hostname_normalized}). */
  @Column(nullable = false, unique = true)
  public String hostname;

  @Column(name = "is_primary", nullable = false)
  public boolean primary;

  /** Null until the DNS points to us: an unverified domain is ignored by the tenant filter. */
  @Column(name = "verified_at")
  public Instant verifiedAt;
}
