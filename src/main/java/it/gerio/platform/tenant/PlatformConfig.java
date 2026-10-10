package it.gerio.platform.tenant;

import io.smallrye.config.ConfigMapping;
import java.util.OptionalInt;

/**
 * The platform's own domain (PROGETTO.md §5.2, App. B.1), read from {@code app.platform.*}.
 *
 * <p>{@code @ConfigMapping} is validated when the application starts, whether or not anything
 * injects it: in production, without {@code PLATFORM_BASE_DOMAIN}, the application does not start
 * ({@code PlatformConfigTest}).
 */
@ConfigMapping(prefix = "app.platform")
public interface PlatformConfig {

  /** E.g. {@code piattaforma.it}; {@code localhost} in dev and test. Already normalized. */
  String baseDomain();

  /**
   * Port added to redirect URLs, only in dev and test where the app listens on 8080/8081. Never
   * taken from the request: behind the proxy the browser always uses the default port.
   */
  OptionalInt redirectPort();
}
