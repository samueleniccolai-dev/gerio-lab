package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import it.gerio.platform.tenant.TestShops;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 005: a real headless Chromium opens the demo product page at 390x844, reads the h1 and saves
 * target/e2e-screenshots/storefront-product.png.
 */
@QuarkusTest
class Giro005CatalogVisualAcceptanceTest {

  private static final int WIDTH = 390;
  private static final int HEIGHT = 844;
  private static final Path SHOTS = Path.of("target", "e2e-screenshots");
  private static final Path PRODUCT_PNG = SHOTS.resolve("storefront-product.png");
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
    Files.deleteIfExists(PRODUCT_PNG);
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
  void setUp() throws IOException, SQLException {
    shops = new TestShops(dataSource);
    new Giro005DemoData(dataSource).runSeed();
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
  void theProductPageOpensOnAPhoneAndIsCaptured() throws IOException {
    String url = "http://demo.localhost:" + base.getPort() + "/prodotti/bouquet-di-stagione";
    Page page = context.newPage();
    Response response = page.navigate(url);
    assertNotNull(response, "no response for " + url);
    assertEquals(200, response.status(), "status for " + url);
    assertEquals("Bouquet di stagione", page.locator("h1").innerText().strip());

    Files.createDirectories(SHOTS);
    page.screenshot(new Page.ScreenshotOptions().setPath(PRODUCT_PNG).setFullPage(false));
    assertTrue(Files.isRegularFile(PRODUCT_PNG), "missing " + PRODUCT_PNG);
    byte[] png = Files.readAllBytes(PRODUCT_PNG);
    assertTrue(png.length > 24, "PNG too short");
    assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(png, 8), "not a PNG");
    ByteBuffer header = ByteBuffer.wrap(png, 16, 8);
    assertEquals(WIDTH, header.getInt(), "PNG width");
    assertEquals(HEIGHT, header.getInt(), "PNG height");
  }
}
