package it.gerio.platform.cart;

import it.gerio.platform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** One variant in a cart, with its quantity (1..20). The price is read from the catalog. */
@Entity
@Table(name = "cart_item")
public class CartItem extends TenantScopedEntity {

  @Column(name = "cart_id", nullable = false, updatable = false)
  public long cartId;

  @Column(name = "variant_id", nullable = false, updatable = false)
  public long variantId;

  @Column(nullable = false)
  public int quantity;
}
