package it.gerio.platform.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

// Plain unit test: TenantContext rules, no Quarkus needed.
class TenantContextTest {

  private static final ZoneId ROME = ZoneId.of("Europe/Rome");
  private static final CurrentTenant.Shop A = new CurrentTenant.Shop("tenant-a", "A", ROME);
  private static final CurrentTenant.Shop B = new CurrentTenant.Shop("tenant-b", "B", ROME);

  private final TenantContext context = new TenantContext();

  @Test
  void requireFailsWhenEmpty() {
    assertThrows(IllegalStateException.class, context::require);
  }

  @Test
  void setTwiceFails() {
    context.set(A);

    assertThrows(IllegalStateException.class, () -> context.set(B));
    assertEquals(A, context.require());
  }

  @Test
  void switchFromPlatformToShopIsAllowedOnce() {
    context.set(CurrentTenant.PLATFORM);

    context.switchTo(A);

    assertEquals(A, context.require());
    assertThrows(IllegalStateException.class, () -> context.switchTo(B));
    assertEquals(A, context.require());
  }

  @Test
  void switchFromShopToShopFails() {
    context.set(A);

    assertThrows(IllegalStateException.class, () -> context.switchTo(B));
  }

  @Test
  void switchWithoutContextFails() {
    assertThrows(IllegalStateException.class, () -> context.switchTo(A));
  }

  @Test
  void shopCannotUseThePlatformIdOrABlankId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentTenant.Shop(CurrentTenant.PLATFORM_ID, "x", ROME));
    assertThrows(IllegalArgumentException.class, () -> new CurrentTenant.Shop(" ", "x", ROME));
  }
}
