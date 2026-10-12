package it.gerio.platform.order;

import it.gerio.platform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An order (SPEC 6). Immutable after creation except for the status and the state of the Stripe
 * session attempts. Amounts in cents.
 */
@Entity
@Table(name = "customer_order")
public class CustomerOrder extends TenantScopedEntity {

  @Column(name = "cart_id")
  public Long cartId;

  /**
   * 0 before the first try; the idempotency key of the Stripe call is {@code order-id-attempt-n}.
   */
  @Column(name = "checkout_attempt", nullable = false)
  public int checkoutAttempt;

  /** Expiry sent to Stripe for the current attempt; null after a failed attempt. */
  @Column(name = "checkout_expires_at")
  public Instant checkoutExpiresAt;

  @Column(name = "checkout_url")
  public String checkoutUrl;

  @Column(nullable = false, updatable = false)
  public String number;

  @Column(name = "public_token", nullable = false, updatable = false)
  public String publicToken;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public OrderStatus status;

  @Column(name = "fulfillment_type", nullable = false, updatable = false)
  public String fulfillmentType;

  @Column(name = "customer_name", nullable = false, updatable = false)
  public String customerName;

  @Column(name = "customer_email", nullable = false, updatable = false)
  public String customerEmail;

  @Column(name = "customer_phone", updatable = false)
  public String customerPhone;

  @Column(name = "delivery_date", nullable = false, updatable = false)
  public LocalDate deliveryDate;

  @Column(name = "subtotal_cents", nullable = false, updatable = false)
  public long subtotalCents;

  @Column(name = "delivery_fee_cents", nullable = false, updatable = false)
  public long deliveryFeeCents;

  @Column(name = "total_cents", nullable = false, updatable = false)
  public long totalCents;

  /** JSON: taxable amount and tax per VAT rate. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "vat_breakdown", nullable = false, updatable = false)
  public String vatBreakdown;

  @Column(name = "terms_version", nullable = false, updatable = false)
  public String termsVersion;

  @Column(name = "hold_expires_at")
  public Instant holdExpiresAt;

  @Column(name = "stripe_checkout_session_id")
  public String stripeCheckoutSessionId;
}
