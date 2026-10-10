package it.gerio.platform.tenant;

import java.time.ZoneId;

/**
 * What the tenant filter needs to know about the shop behind a host.
 *
 * @param primaryHost where the shop wants to be seen: its verified primary custom domain, or else
 *     its free subdomain {@code slug.<base>}
 */
public record ResolvedShop(
    String id, String displayName, ZoneId timezone, TenantStatus status, String primaryHost) {

  public CurrentTenant.Shop toCurrentTenant() {
    return new CurrentTenant.Shop(id, displayName, timezone);
  }
}
