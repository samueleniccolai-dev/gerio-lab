package it.gerio.platform.tenant;

import it.gerio.platform.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A shop. Global table: no {@code @TenantId}, it is the tenant itself (PROGETTO.md §5.1).
 *
 * <p><b>Not filtered by shop.</b> From shop A's request, {@code Tenant.findById(idOfB)} returns B.
 * In a shop request, look up only the current shop, by {@code tenantContext.require().id()}, never
 * by an id taken from the request; and every endpoint that reads this entity needs its A/B
 * isolation test (ADR-0004).
 *
 * <p>The id is a UUID in text form, the declared exception to {@code EntityBase} (ADR-0003): the
 * Quarkus {@code TenantResolver} returns a String.
 *
 * <p>Only the columns used so far are mapped. The others (legal data, Stripe, {@code theme} and
 * {@code settings} as jsonb) keep their database defaults and get mapped with the feature that
 * needs them.
 */
@Entity
@Table(name = "tenant")
public class Tenant extends AuditedEntity {

  @Id private String id;

  @Column(nullable = false, unique = true)
  public String slug;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public TenantStatus status;

  @Column(name = "display_name", nullable = false)
  public String displayName;

  @Column(name = "legal_name", nullable = false)
  public String legalName;

  @Column(nullable = false)
  public String email;

  @Column(nullable = false)
  public String timezone;

  // For JPA only.
  protected Tenant() {}

  /** A new shop, in DRAFT like the database default. */
  public static Tenant create(String slug, String displayName, String legalName, String email) {
    Tenant tenant = new Tenant();
    tenant.id = UUID.randomUUID().toString();
    tenant.slug = slug;
    tenant.status = TenantStatus.DRAFT;
    tenant.displayName = displayName;
    tenant.legalName = legalName;
    tenant.email = email;
    tenant.timezone = "Europe/Rome";
    return tenant;
  }

  public String getId() {
    return id;
  }
}
