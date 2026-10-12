package it.gerio.platform.acceptance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Giro 011 test resource: the fake Stripe API of giro 010 plus the invented webhook signing secret
 * of the plan ({@code whsec_giro011_finto}) in {@code app.stripe.connect-webhook-secret}.
 */
public class Giro011FakeStripe extends Giro010FakeStripe {

  @Override
  public Map<String, String> start() {
    Map<String, String> config = new LinkedHashMap<>(super.start());
    config.put("app.stripe.connect-webhook-secret", Giro011Webhooks.SECRET);
    return config;
  }
}
