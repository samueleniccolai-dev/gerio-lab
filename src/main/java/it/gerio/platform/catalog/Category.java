package it.gerio.platform.catalog;

import it.gerio.platform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

/** A group of products shown on the shop's catalog (PROGETTO.md §6). */
@Entity
public class Category extends TenantScopedEntity {

  @Column(nullable = false)
  public String name;

  @Column(nullable = false)
  public String slug;

  @Column(nullable = false)
  public int position;

  @Column(nullable = false)
  public boolean visible;
}
