package it.gerio.platform.order;

import it.gerio.platform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** One line of an order: name and price as they were when the order was made. */
@Entity
@Table(name = "order_item")
public class OrderItem extends TenantScopedEntity {

  @Column(name = "order_id", nullable = false, updatable = false)
  public long orderId;

  @Column(name = "variant_id", updatable = false)
  public Long variantId;

  @Column(name = "name_snapshot", nullable = false, updatable = false)
  public String nameSnapshot;

  @Column(name = "unit_price_cents", nullable = false, updatable = false)
  public long unitPriceCents;

  @Column(nullable = false, updatable = false)
  public int quantity;

  @Column(name = "vat_rate_bp", nullable = false, updatable = false)
  public int vatRateBp;

  @Column(name = "line_total_cents", nullable = false, updatable = false)
  public long lineTotalCents;
}
