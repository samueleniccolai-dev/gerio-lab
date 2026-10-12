package it.gerio.platform.acceptance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Giro 012 test resource: the fake Stripe API of giro 010 with the invented keys of the plan. The
 * secret key comes from the profile ({@code secret-key} init argument: {@code sk_test_…}, {@code
 * sk_live_…} or {@code rk_live_…}); the webhook signing secret is {@code whsec_giro012_finto}.
 */
public class Giro012FakeStripe extends Giro010FakeStripe {

  static final String KEY_ARG = "secret-key";

  private String secretKey = Giro012Profiles.TEST_KEY;

  @Override
  public void init(Map<String, String> initArgs) {
    if (initArgs != null && initArgs.containsKey(KEY_ARG)) {
      secretKey = initArgs.get(KEY_ARG);
    }
  }

  @Override
  public Map<String, String> start() {
    Map<String, String> config = new LinkedHashMap<>(super.start());
    config.put("app.stripe.secret-key", secretKey);
    config.put("app.stripe.connect-webhook-secret", Giro012Profiles.WEBHOOK_SECRET);
    return config;
  }
}
