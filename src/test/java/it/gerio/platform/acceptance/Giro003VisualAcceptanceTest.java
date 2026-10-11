package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import it.gerio.platform.tenant.TenantStatus;
import it.gerio.platform.tenant.TestShops;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 003 (goal T2): a real headless Chromium opens the demo shop and the platform landing, reads
 * the DOM and saves one mobile screenshot each in target/e2e-screenshots/ (uploaded by CI as the
 * "e2e-screenshots" artifact). Black box: only URLs, the DOM and the PNG files. A browser that does
 * not start is a failure, never a skip.
 */
@QuarkusTest
class Giro003VisualAcceptanceTest {

  private static final String DEMO_ID = "00000000-0000-0000-0000-0000000000d1";
  private static final String DEMO_SLUG = "fioraia-demo";
  private static final String DEMO_NAME = "Le Rose di Lucia";
  private static final String OTHER_ID = "00000000-0000-0000-0000-0000000000d2";
  private static final String OTHER_SLUG = "fiori-di-marco";
  private static final String OTHER_NAME = "Fiori di Marco";

  private static final int WIDTH = 390;
  private static final int HEIGHT = 844;
  private static final Path SHOTS = Path.of("target", "e2e-screenshots");
  private static final Path STOREFRONT_PNG = SHOTS.resolve("storefront-home.png");
  private static final Path LANDING_PNG = SHOTS.resolve("platform-landing.png");
  private static final byte[] PNG_SIGNATURE = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
  };

  private static Playwright playwright;
  private static Browser browser;

  @TestHTTPResource("/")
  URL base;

  @Inject DataSource dataSource;

  private TestShops shops;
  private BrowserContext context;

  @BeforeAll
  static void startChromium() throws IOException {
    Files.deleteIfExists(STOREFRONT_PNG);
    Files.deleteIfExists(LANDING_PNG);
    playwright = Playwright.create();
    browser = playwright.chromium().launch();
  }

  @AfterAll
  static void stopChromium() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  @BeforeEach
  void setUp() throws SQLException {
    shops = new TestShops(dataSource);
    shops.insertTenant(DEMO_ID, DEMO_SLUG, DEMO_NAME, TenantStatus.ACTIVE);
    shops.insertTenant(OTHER_ID, OTHER_SLUG, OTHER_NAME, TenantStatus.ACTIVE);
    context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setViewportSize(WIDTH, HEIGHT)
                .setDeviceScaleFactor(1)
                .setIsMobile(true)
                .setHasTouch(true));
  }

  @AfterEach
  void tearDown() throws SQLException {
    if (context != null) {
      context.close();
    }
    shops.deleteAll();
  }

  @Test
  void theBrowserIsARealChromium() {
    assertEquals("chromium", browser.browserType().name());
    Page page = context.newPage();
    String userAgent = (String) page.evaluate("() => navigator.userAgent");
    assertTrue(userAgent.contains("Chrome/"), "not a Chromium user agent: " + userAgent);
  }

  @Test
  void theDemoShopHostShowsItsNameAndNeverTheOtherShop() throws IOException {
    Page page = open("http://" + DEMO_SLUG + ".localhost:" + base.getPort() + "/");
    assertEquals(DEMO_NAME, page.locator("h1").innerText().strip());
    assertFalse(bodyText(page).contains(OTHER_NAME), "the demo shop shows " + OTHER_NAME);
    assertMobileScreenshot(page, STOREFRONT_PNG);
  }

  @Test
  void theOtherShopHostShowsItsOwnNameAndNeverTheDemoShop() {
    Page page = open("http://" + OTHER_SLUG + ".localhost:" + base.getPort() + "/");
    assertEquals(OTHER_NAME, page.locator("h1").innerText().strip());
    assertFalse(bodyText(page).contains(DEMO_NAME), "the other shop shows " + DEMO_NAME);
  }

  @Test
  void thePlatformHostShowsTheLandingAndNoShopName() throws IOException {
    Page page = open("http://localhost:" + base.getPort() + "/");
    String text = bodyText(page);
    assertTrue(text.contains("Negozi locali online"), "landing text missing: " + text);
    assertFalse(text.contains(DEMO_NAME), "the landing shows " + DEMO_NAME);
    assertFalse(text.contains(OTHER_NAME), "the landing shows " + OTHER_NAME);
    assertMobileScreenshot(page, LANDING_PNG);
  }

  // Fatto quando: docs/verifiche.md records the dependency, its version, a source and a date.
  @Test
  void verificheRecordsThePlaywrightVersionWithSourceAndDate() throws IOException {
    String verifiche = Files.readString(Path.of("docs", "verifiche.md"), StandardCharsets.UTF_8);
    boolean recorded =
        Arrays.stream(verifiche.split("\n"))
            .anyMatch(
                line ->
                    line.startsWith("| 2026-")
                        && line.contains("com.microsoft.playwright")
                        && line.contains("1.63.0")
                        && Pattern.compile("(?i)maven|playwright\\.dev|github\\.com")
                            .matcher(line)
                            .find());
    assertTrue(
        recorded,
        "docs/verifiche.md has no dated row for com.microsoft.playwright 1.63.0 with a source");
  }

  private Page open(String url) {
    Page page = context.newPage();
    Response response = page.navigate(url);
    assertNotNull(response, "no response for " + url);
    assertEquals(200, response.status(), "status for " + url);
    return page;
  }

  private static String bodyText(Page page) {
    return page.locator("body").innerText();
  }

  private static void assertMobileScreenshot(Page page, Path file) throws IOException {
    Files.createDirectories(SHOTS);
    page.screenshot(new Page.ScreenshotOptions().setPath(file).setFullPage(false));
    assertTrue(Files.isRegularFile(file), "missing " + file);
    byte[] png = Files.readAllBytes(file);
    assertTrue(png.length > 24, "PNG too short: " + file);
    assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(png, 8), "not a PNG: " + file);
    ByteBuffer header = ByteBuffer.wrap(png, 16, 8);
    assertEquals(WIDTH, header.getInt(), "PNG width of " + file);
    assertEquals(HEIGHT, header.getInt(), "PNG height of " + file);
  }
}
