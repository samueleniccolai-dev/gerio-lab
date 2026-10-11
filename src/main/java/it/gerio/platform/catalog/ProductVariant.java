package it.gerio.platform.catalog;

import it.gerio.platform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A size or version of a product with its price in cents, VAT included. */
@Entity
@Table(name = "product_variant")
public class ProductVariant extends TenantScopedEntity {

  @Column(name = "product_id", nullable = false)
  public long productId;

  @Column(nullable = false)
  public String name;

  @Column(name = "price_cents", nullable = false)
  public long priceCents;

  @Column(nullable = false)
  public boolean active;

  @Column(nullable = false)
  public int position;
}
