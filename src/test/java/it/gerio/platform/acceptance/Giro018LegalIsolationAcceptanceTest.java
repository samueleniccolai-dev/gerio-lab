package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro008CartData.DEMO;
import static it.gerio.platform.acceptance.Giro008CartData.MARCO;
import static it.gerio.platform.acceptance.Giro008CartData.PLATFORM;
import static it.gerio.platform.acceptance.Giro018Legal.DEMO_PEC;
import static it.gerio.platform.acceptance.Giro018Legal.DEMO_VAT;
import static it.gerio.platform.acceptance.Giro018Legal.MARCO_LEGAL_NAME;
import static it.gerio.platform.acceptance.Giro018Legal.NEW_PAGES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 018 (goal T8): Marco's shop shows only its own seller data in the legal pages and in the
 * footer, also right after a request to the demo; the platform host has no legal pages of a shop
 * (docs/lab/giri/018-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro018LegalIsolationAcceptanceTest {

  private static final List<String> FOREIGN = List.of("Glicine", DEMO_VAT, DEMO_PEC, "(LU)");

  @Inject DataSource dataSource;

  private Giro014Data data;

  @BeforeEach
  void setUp() throws Exception {
    data = new Giro014Data(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  @Test
  void marcosPagesAndFooterShowMarcoOnlyEvenRightAfterTheDemo() {
    List<String> paths = new ArrayList<>(NEW_PAGES);
    paths.add("/");
    for (String path : paths) {
      Giro018Legal.assertDemoFooter(Giro018Legal.page(DEMO, path), "demo GET " + path);

      String html = Giro018Legal.page(MARCO, path);
      String where = "Marco GET " + path;
      String text = Giro018Legal.text(html);
      assertTrue(text.contains(MARCO_LEGAL_NAME), where + ": no «" + MARCO_LEGAL_NAME + "»");
      for (String foreign : FOREIGN) {
        assertFalse(text.contains(foreign), where + ": shows the demo's «" + foreign + "»");
      }
      String footer = Giro018Legal.footer(html, where);
      String footerText = Giro018Legal.text(footer);
      assertTrue(footerText.contains(MARCO_LEGAL_NAME), where + ": footer without Marco's name");
      assertFalse(footerText.contains("P. IVA"), where + ": orphan «P. IVA» label in the footer");
      Giro018Legal.assertLinks(footer, where);
    }
  }

  @Test
  void thePlatformHostHasNoLegalPagesOfAShop() {
    for (String path : NEW_PAGES) {
      assertEquals(
          404, Giro018Legal.get(PLATFORM, path).statusCode(), "GET " + path + " on " + PLATFORM);
    }
  }
}
