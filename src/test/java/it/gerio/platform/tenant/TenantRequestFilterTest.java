package it.gerio.platform.tenant;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.tenant.TestShops.SHOP_A;
import static it.gerio.platform.tenant.TestShops.SHOP_B;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// The tenant filter end to end (PROGETTO.md §5.2, ADR-0004). Base domain in
// tests: localhost. Each request sends its own Host header; TenantEchoResource
// answers with the tenant the filter chose.
@QuarkusTest
class TenantRequestFilterTest {

  private static final String DRAFT = "00000000-0000-0000-0000-0000000000dd";
  private static final String SUSPENDED = "00000000-0000-0000-0000-0000000000ee";
  private static final String UNVERIFIED = "00000000-0000-0000-0000-0000000000ff";
  private static final String HIJACKER = "00000000-0000-0000-0000-000000000011";
  private static final String PATH = "/test/tenant/x";
  private static final int PORT = 8081;

  @Inject DataSource dataSource;

  @TestHTTPResource("/")
  URL root;

  private TestShops shops;

  @BeforeEach
  void shops() throws SQLException {
    shops = new TestShops(dataSource);
    // A: only its free subdomain fioraia-a.localhost.
    shops.insertTenant(SHOP_A, "fioraia-a", "Fioraia A", TenantStatus.ACTIVE);
    // B: primary custom domain, a second verified one, an unverified one.
    shops.insertTenant(SHOP_B, "fioraia-b", "Fioraia B", TenantStatus.ACTIVE);
    shops.insertDomain(SHOP_B, "fioraia-b.it", true, true);
    shops.insertDomain(SHOP_B, "fiori-b.it", false, true);
    shops.insertDomain(SHOP_B, "nuovo-b.it", false, false);
    shops.insertTenant(DRAFT, "bozza", "Bozza", TenantStatus.DRAFT);
    // Suspended with a primary custom domain: its slug would normally redirect.
    shops.insertTenant(SUSPENDED, "sospeso", "Sospeso", TenantStatus.SUSPENDED);
    shops.insertDomain(SUSPENDED, "sospeso.it", true, true);
    // Primary custom domain not verified yet: must be ignored everywhere.
    shops.insertTenant(UNVERIFIED, "fioraia-e", "Fioraia E", TenantStatus.ACTIVE);
    shops.insertDomain(UNVERIFIED, "fioraia-e.it", true, false);
    // A row that should never exist: A's subdomain as H's primary domain.
    shops.insertTenant(HIJACKER, "fioraia-h", "Fioraia H", TenantStatus.ACTIVE);
    shops.insertDomain(HIJACKER, "fioraia-a.localhost", true, true);
  }

  @AfterEach
  void cleanUp() throws SQLException {
    shops.deleteAll();
  }

  // --- The platform ---

  @ParameterizedTest
  @ValueSource(strings = {"localhost", "localhost:8081", "LOCALHOST", "www.localhost"})
  void theBaseDomainIsThePlatform(String host) {
    get(host, PATH).statusCode(200).body(equalTo(CurrentTenant.PLATFORM_ID));
  }

  // --- Shops by slug and by custom domain ---

  @ParameterizedTest
  @ValueSource(
      strings = {"fioraia-a.localhost", "Fioraia-A.LOCALHOST:8081", "fioraia-a.localhost."})
  void theSlugSubdomainLeadsToItsShop(String host) {
    get(host, PATH).statusCode(200).body(equalTo(SHOP_A));
  }

  @Test
  void aVerifiedPrimaryCustomDomainLeadsToItsShop() {
    get("fioraia-b.it", PATH).statusCode(200).body(equalTo(SHOP_B));
  }

  // The shop may have pointed only "www" to us (CNAME): no redirect to the bare name.
  @Test
  void theWwwFormOfTheCustomDomainIsServedWithoutRedirect() {
    get("www.fioraia-b.it", PATH).statusCode(200).body(equalTo(SHOP_B));
  }

  // --- Redirects to the primary host ---

  @Test
  void aSecondaryDomainRedirectsToThePrimaryForOneHour() {
    get("fiori-b.it", "/test/tenant/x?colore=rosso")
        .statusCode(301)
        .header("Location", "http://fioraia-b.it:" + PORT + "/test/tenant/x?colore=rosso")
        .header("Cache-Control", "max-age=3600");
  }

  @Test
  void theSlugOfAShopWithACustomDomainRedirectsToIt() {
    get("fioraia-b.localhost", PATH)
        .statusCode(301)
        .header("Location", "http://fioraia-b.it:" + PORT + PATH);
  }

  @Test
  void aPostToASecondaryDomainKeepsItsMethod() {
    given()
        .redirects()
        .follow(false)
        .header("Host", "fiori-b.it")
        .when()
        .post(PATH)
        .then()
        .statusCode(308)
        .header("Location", "http://fioraia-b.it:" + PORT + PATH);
  }

  // %3F is a "?" inside the path, %2F a "/" inside a segment: decoding them would
  // redirect to a different resource.
  @Test
  void theRedirectKeepsPathAndQueryEncoded() {
    given()
        .urlEncodingEnabled(false)
        .redirects()
        .follow(false)
        .header("Host", "fiori-b.it")
        .when()
        .get("/test/tenant/a%3Fb%2Fc?q=1%262")
        .then()
        .statusCode(301)
        .header("Location", "http://fioraia-b.it:" + PORT + "/test/tenant/a%3Fb%2Fc?q=1%262");
  }

  // --- Unverified domains are ignored ---

  @Test
  void anUnverifiedDomainIsNotFound() {
    get("nuovo-b.it", PATH).statusCode(404);
    get("fioraia-e.it", PATH).statusCode(404);
  }

  @Test
  void anUnverifiedPrimaryDomainIsNeverARedirectTarget() {
    get("fioraia-e.localhost", PATH).statusCode(200).body(equalTo(UNVERIFIED));
  }

  // --- tenant_domain rows below the base domain are ignored ---

  @Test
  void aDomainRowBelowTheBaseCannotStealTheSlug() {
    get("fioraia-a.localhost", PATH).statusCode(200).body(equalTo(SHOP_A));
  }

  @Test
  void aDomainRowBelowTheBaseIsNeverARedirectTarget() {
    // H's "primary" is A's subdomain: following it would send H's customers to A.
    get("fioraia-h.localhost", PATH).statusCode(200).body(equalTo(HIJACKER));
  }

  // --- Not found and not available ---

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sconosciuto.it", // no shop
        "a.fioraia-a.localhost", // deeper than one label below the base
        "xlocalhost", // only ends with the base: not a subdomain
        "127.0.0.1", // IP address
        "fioraia_a.localhost", // not a hostname
      })
  void anUnknownOrInvalidHostIsAGenericNotFound(String host) {
    get(host, PATH)
        .statusCode(404)
        .body(containsString("Pagina non trovata"))
        .body(not(containsString("Fioraia")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"bozza.localhost", "sospeso.localhost", "sospeso.it"})
  void aShopThatIsNotActiveIsUnavailableAndNeverRedirects(String host) {
    get(host, PATH)
        .statusCode(503)
        .header("Location", nullValue())
        .body(containsString("Temporaneamente non disponibile"))
        .body(not(containsString("Sospeso")))
        .body(not(containsString("Bozza")));
  }

  // HTTP/1.0 allows a request without Host; RestAssured always sends one, so this
  // test writes the request by hand on a socket.
  @Test
  void aRequestWithoutHostIsNotFound() throws IOException {
    try (Socket socket = new Socket(root.getHost(), root.getPort())) {
      OutputStream out = socket.getOutputStream();
      out.write(("GET " + PATH + " HTTP/1.0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      out.flush();
      InputStream in = socket.getInputStream();
      String statusLine = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
      assertTrue(statusLine.endsWith("404"), statusLine);
    }
  }

  // Written by hand: RestAssured cannot send a request-target without "/".
  // End-to-end property: no Location ever points to another host. Honest note:
  // today Quarkus REST routes no such target (plain 404, verified 27/9 even with a
  // catch-all test route), so the filter's own "starts with /" guard is not
  // reached here; it stays as a one-line defense in case routing changes.
  @Test
  void aRequestTargetWithoutSlashNeverRedirectsElsewhere() throws IOException {
    String response =
        rawRequest("GET @evil.example HTTP/1.1\r\nHost: fiori-b.it\r\nConnection: close\r\n\r\n");
    assertTrue(!response.contains("evil.example"), response);
  }

  @Test
  void aNonBlockingEndpointFailsEvenOnThePlatformHost() {
    get("localhost", "/test/non-blocking").statusCode(500);
  }

  private String rawRequest(String request) throws IOException {
    try (Socket socket = new Socket(root.getHost(), root.getPort())) {
      OutputStream out = socket.getOutputStream();
      out.write(request.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
    }
  }

  private static ValidatableResponse get(String host, String path) {
    return given().redirects().follow(false).header("Host", host).when().get(path).then();
  }
}
