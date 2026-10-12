package it.gerio.platform.payment;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

/**
 * Stripe settings, from {@code app.stripe.*}. All optional: without a key the application starts
 * and the checkout is simply off, as for a shop whose charges are not enabled.
 */
@ConfigMapping(prefix = "app.stripe")
public interface StripeConfig {

  /** The platform's secret key ({@code sk_test_...}); never logged. */
  Optional<String> secretKey();

  /** Only for tests, which point the client at a fake Stripe. Absent means Stripe's own API. */
  Optional<String> apiBase();

  /** Signing secret of the Connect webhook endpoint; used from giro 011. */
  Optional<String> connectWebhookSecret();
}
