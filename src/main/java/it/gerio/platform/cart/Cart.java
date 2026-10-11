package it.gerio.platform.cart;

import it.gerio.platform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** An anonymous cart, found through the random token kept in the {@code cart} cookie. */
@Entity
@Table(name = "cart")
public class Cart extends TenantScopedEntity {

  @Column(nullable = false, updatable = false)
  public String token;
}
