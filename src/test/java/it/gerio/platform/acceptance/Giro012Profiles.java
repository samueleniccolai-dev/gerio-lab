package it.gerio.platform.acceptance;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Giro 012 test profiles (docs/lab/giri/012-piano.md). Every profile switches the demo seed on,
 * runs the fake Stripe with an invented key and writes the application log to its own file under
 * {@code target/} (format {@code LEVEL category message}), read back by {@link Giro012Log}.
 */
public final class Giro012Profiles {

  static final String ACCOUNT = "acct_1GlicineDemoTest0";
  static final String TEST_KEY = "sk_test_giro012_finta";
  static final String LIVE_KEY = "sk_live_giro012_finta";
  static final String LIVE_RESTRICTED_KEY = "rk_live_giro012_finta";
  static final String WEBHOOK_SECRET = "whsec_giro012_finto";

  /** Common part of the invented keys: must never appear in the log. */
  static final String KEY_MARK = "giro012_finta";

  private Giro012Profiles() {}

  private abstract static class Base implements QuarkusTestProfile {

    abstract String name();

    abstract String secretKey();

    /** The demo account in app.demo.stripe-account-id; empty means "no value". */
    abstract String account();

    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> config = new LinkedHashMap<>();
      config.put("app.demo.seed", "true");
      config.put("app.demo.stripe-account-id", account());
      config.put("quarkus.log.file.enabled", "true");
      config.put(
          "quarkus.log.file.path", "target/giro012-" + name() + "-" + System.nanoTime() + ".log");
      config.put("quarkus.log.file.format", "%p %c %s%e%n");
      return config;
    }

    @Override
    public List<TestResourceEntry> testResources() {
      return List.of(
          new TestResourceEntry(
              Giro012FakeStripe.class, Map.of(Giro012FakeStripe.KEY_ARG, secretKey())));
    }
  }

  /** Seed on, account acct_1GlicineDemoTest0, test key. */
  public static class WithAccount extends Base {
    @Override
    String name() {
      return "with-account";
    }

    @Override
    String secretKey() {
      return TEST_KEY;
    }

    @Override
    String account() {
      return ACCOUNT;
    }
  }

  /** Seed on, no account value, test key. */
  public static class WithoutAccount extends Base {
    @Override
    String name() {
      return "without-account";
    }

    @Override
    String secretKey() {
      return TEST_KEY;
    }

    @Override
    String account() {
      return "";
    }
  }

  /** Seed on, account acct_1GlicineDemoTest0, live secret key (to be refused). */
  public static class LiveSecretKey extends Base {
    @Override
    String name() {
      return "live-secret-key";
    }

    @Override
    String secretKey() {
      return LIVE_KEY;
    }

    @Override
    String account() {
      return ACCOUNT;
    }
  }

  /** Seed on, account acct_1GlicineDemoTest0, live restricted key (to be refused). */
  public static class LiveRestrictedKey extends Base {
    @Override
    String name() {
      return "live-restricted-key";
    }

    @Override
    String secretKey() {
      return LIVE_RESTRICTED_KEY;
    }

    @Override
    String account() {
      return ACCOUNT;
    }
  }
}
