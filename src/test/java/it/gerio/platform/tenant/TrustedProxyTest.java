package it.gerio.platform.tenant;

import static it.gerio.platform.tenant.TestShops.SHOP_A;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

// The test client plays Caddy: a profile trusts localhost as the proxy.
// X-Forwarded-Proto is used; the shop still comes only from the Host header,
// because enable-forwarded-host is off (ADR-0004). This is what stops any
// container on the proxy network from choosing the shop.
@QuarkusTest
@TestProfile(TrustedProxyTest.TrustLocalhost.class)
class TrustedProxyTest extends ProxyTestSupport {

  public static class TrustLocalhost implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.http.proxy.trusted-proxies", "127.0.0.0/8,::1");
    }
  }

  @Test
  void xForwardedHostCannotSwitchShop() {
    request("fioraia-a.localhost")
        .header("X-Forwarded-Host", "fioraia-b.localhost")
        .get(PATH)
        .then()
        .statusCode(200)
        .body(equalTo(SHOP_A));
  }

  // The standard header (RFC 7239) is not enabled either: Caddy doesn't send it.
  @Test
  void forwardedHeaderCannotSwitchShop() {
    request("fioraia-a.localhost")
        .header("Forwarded", "host=fioraia-b.localhost;proto=https")
        .get(PATH)
        .then()
        .statusCode(200)
        .body(equalTo(SHOP_A));
  }

  // Caddy terminates TLS: without this, every redirect would point to http://.
  // Also proves the profile works: this test fails if localhost is not trusted.
  @Test
  void xForwardedProtoMakesRedirectsHttps() {
    request("fiori-b.it")
        .header("X-Forwarded-Proto", "https")
        .get(PATH)
        .then()
        .statusCode(301)
        .header("Location", "https://fioraia-b.it:8081" + PATH);
  }
}
