package it.gerio.platform.delivery;

import it.gerio.platform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;

/** An area where the shop delivers (SPEC 6), by postal code or municipality. Amounts in cents. */
@Entity
@Table(name = "delivery_zone")
public class DeliveryZone extends TenantScopedEntity {

  @Column(nullable = false)
  public String name;

  @Column(name = "postal_codes", nullable = false)
  public List<String> postalCodes;

  @Column(nullable = false)
  public List<String> municipalities;

  @Column(name = "fee_cents", nullable = false)
  public long feeCents;

  @Column(name = "min_order_cents", nullable = false)
  public long minOrderCents;

  @Column(nullable = false)
  public boolean active;
}
