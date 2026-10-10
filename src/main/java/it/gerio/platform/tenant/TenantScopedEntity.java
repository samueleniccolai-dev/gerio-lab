package it.gerio.platform.tenant;

import it.gerio.platform.common.EntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

/**
 * Base of every entity that belongs to one shop (ADR-0003, PROGETTO.md §5.1). Extending it makes
 * {@code @TenantId} impossible to forget.
 *
 * <p>Hibernate fills {@code tenant_id} on insert with the tenant of the session and adds {@code
 * tenant_id = ?} to every query and every load by id. Native SQL is <b>not</b> filtered.
 *
 * <p>The table must follow ADR-0003 point 8: {@code tenant_id text not null references tenant(id)},
 * {@code unique (tenant_id, id)}, indexes starting with {@code tenant_id}. {@code SchemaRulesTest}
 * checks it for every table.
 */
@MappedSuperclass
public abstract class TenantScopedEntity extends EntityBase {

  @TenantId
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private String tenantId;

  public String getTenantId() {
    return tenantId;
  }
}
