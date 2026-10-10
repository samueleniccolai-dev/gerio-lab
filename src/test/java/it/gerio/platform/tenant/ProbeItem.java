package it.gerio.platform.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Test-only shop entity (table from {@code db/test-migration/R__probe_item.sql}). */
@Entity
@Table(name = "probe_item")
public class ProbeItem extends TenantScopedEntity {

  @Column(nullable = false)
  public String name;
}
