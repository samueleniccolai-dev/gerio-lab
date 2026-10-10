package it.gerio.platform.tenant;

import static it.gerio.platform.tenant.TestShops.SHOP_A;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

// A client outside the proxy network (tests connect from localhost, not from
// 172.16.0.0/12): every forwarded header is ignored (ADR-0004).
@QuarkusTest
class ProxyHeadersTest extends ProxyTestSupport {

  @Test
  void xForwardedHostCannotSwitchShop() {
    request("fioraia-a.localhost")
        .header("X-Forwarded-Host", "fioraia-b.localhost")
        .get(PATH)
        .then()
        .statusCode(200)
        .body(equalTo(SHOP_A));
  }

  @Test
  void xForwardedProtoIsIgnored() {
    request("fiori-b.it")
        .header("X-Forwarded-Proto", "https")
        .get(PATH)
        .then()
        .statusCode(301)
        .header("Location", "http://fioraia-b.it:8081" + PATH);
  }
}
