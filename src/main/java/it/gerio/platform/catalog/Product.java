package it.gerio.platform.catalog;

import it.gerio.platform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

/** A product of the shop; its prices live in {@link ProductVariant} (PROGETTO.md §6). */
@Entity
public class Product extends TenantScopedEntity {

  @Column(name = "category_id")
  public Long categoryId;

  @Column(nullable = false)
  public String name;

  @Column(nullable = false)
  public String slug;

  public String description;

  @Column(name = "vat_rate_bp", nullable = false)
  public int vatRateBp;

  @Column(name = "lead_time_days", nullable = false)
  public int leadTimeDays;

  @Column(name = "withdrawal_excluded", nullable = false)
  public boolean withdrawalExcluded;

  @Column(nullable = false)
  public boolean active;

  @Column(nullable = false)
  public int position;
}
