package it.gerio.platform.order;

import it.gerio.platform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** One change of state of an order, written by {@link OrderStateMachine}. */
@Entity
@Table(name = "order_event")
public class OrderEvent extends TenantScopedEntity {

  @Column(name = "order_id", nullable = false, updatable = false)
  public long orderId;

  @Column(name = "from_status", updatable = false)
  public String fromStatus;

  @Column(name = "to_status", nullable = false, updatable = false)
  public String toStatus;

  public String note;

  @Column(name = "at", nullable = false, updatable = false)
  public Instant at;
}
