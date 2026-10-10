package it.gerio.platform.tenant;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Who the current request works for: the platform itself, or one shop (PROGETTO.md §5.2 point 5).
 *
 * <p>A sealed interface: the compiler knows these are the only two cases, so a {@code switch} over
 * them needs no {@code default} branch.
 */
public sealed interface CurrentTenant {

  /** Reserved id of the platform context. V1 forbids a tenant with this id. */
  String PLATFORM_ID = "__platform__";

  CurrentTenant PLATFORM = new Platform();

  /** The value Hibernate writes and filters on in every {@code tenant_id} column. */
  String id();

  /**
   * Landing page, admin area, webhooks. Global tables work; shop tables look empty, and inserts
   * into them fail on the foreign key to {@code tenant(id)}.
   */
  record Platform() implements CurrentTenant {
    @Override
    public String id() {
      return PLATFORM_ID;
    }
  }

  /** One shop, with what every page needs to render. */
  record Shop(String id, String displayName, ZoneId timezone) implements CurrentTenant {
    public Shop {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(displayName, "displayName");
      Objects.requireNonNull(timezone, "timezone");
      if (id.isBlank() || id.equals(PLATFORM_ID)) {
        throw new IllegalArgumentException("Not a shop id: '" + id + "'");
      }
    }
  }
}
