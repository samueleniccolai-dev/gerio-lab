package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Giro 018 (goal T8, docs/lab/giri/018-piano.md): the legal pages of a shop and the footer of the
 * storefront. Black box: HTTP and plain JDBC only.
 */
final class Giro018Legal {

  static final String SELLER = "/informazioni-venditore";
  static final String TERMS = "/condizioni-di-vendita";
  static final String PRIVACY = "/privacy";
  static final String COOKIES = "/cookie";
  static final String ACCESSIBILITY = "/accessibilita";

  /** The four new pages, in the order of the plan. */
  static final List<String> NEW_PAGES = List.of(SELLER, PRIVACY, COOKIES, ACCESSIBILITY);

  /** The five links of the footer. */
  static final List<String> FOOTER_LINKS = List.of(SELLER, TERMS, PRIVACY, COOKIES, ACCESSIBILITY);

  // Invented data of the demo shop (seed demo/demo-shop.sql).
  static final String DEMO_LEGAL_NAME = "Il Glicine di Lucia Ferri";
  static final String DEMO_STREET = "Via dei Fossi 12";
  static final String DEMO_CITY = "55100 Lucca (LU)";
  static final String DEMO_VAT = "IT00000000000";
  static final String DEMO_VAT_LINE = "P. IVA " + DEMO_VAT;
  static final String DEMO_EMAIL = "ciao@glicine.example";
  static final String DEMO_PHONE = "+39 0583 000000";
  static final String DEMO_PEC = "glicine@pec.example";
  static final String DEMO_REA_LINE = "REA LU-000000";

  static final String MARCO_LEGAL_NAME = "Fiori di Marco Srl";

  private static final Pattern FOOTER = Pattern.compile("(?s)<footer[^>]*>(.*?)</footer>");
  private static final Pattern TAG = Pattern.compile("<[^>]*>");

  private Giro018Legal() {}

  static Response get(String host, String path) {
    return given().redirects().follow(false).header("Host", host).when().get(path);
  }

  /** The HTML of a 200 text/html page. */
  static String page(String host, String path) {
    Response response = get(host, path);
    String html = Giro008Browser.body(response);
    assertEquals(200, response.statusCode(), "GET " + path + " on " + host + "; body: " + html);
    String type = response.getContentType();
    assertTrue(
        type != null && type.startsWith("text/html"), "GET " + path + ": Content-Type " + type);
    return html;
  }

  /** The page as text: tags become spaces, runs of white space (NBSP too) become one space. */
  static String text(String html) {
    return TAG.matcher(html).replaceAll(" ").replaceAll("[\\s\\u00a0]+", " ");
  }

  /** The inner HTML of the only {@code <footer>} of the page; fails if there is none. */
  static String footer(String html, String where) {
    Matcher m = FOOTER.matcher(html);
    assertTrue(m.find(), where + ": no <footer>");
    String found = m.group(1);
    assertTrue(!m.find(), where + ": more than one <footer>");
    return found;
  }

  /** Asserts the demo footer: legal data and the five links. */
  static void assertDemoFooter(String html, String where) {
    String footer = footer(html, where);
    String text = text(footer);
    for (String expected :
        List.of(DEMO_LEGAL_NAME, DEMO_STREET, DEMO_CITY, DEMO_VAT_LINE, DEMO_EMAIL, DEMO_PHONE)) {
      assertTrue(text.contains(expected), where + ": footer without «" + expected + "»: " + text);
    }
    assertLinks(footer, where);
  }

  static void assertLinks(String footer, String where) {
    for (String link : FOOTER_LINKS) {
      assertTrue(
          Pattern.compile("href=[\"']" + Pattern.quote(link) + "[\"']").matcher(footer).find(),
          where + ": footer without a link to " + link);
    }
  }

  // --- JDBC -----------------------------------------------------------------------------------

  static void setTenantColumn(DataSource dataSource, String tenantId, String column, String value)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("update tenant set " + column + " = ? where id = ?")) {
      statement.setString(1, value);
      statement.setString(2, tenantId);
      assertEquals(1, statement.executeUpdate(), "update tenant." + column + " of " + tenantId);
    }
  }

  static String tenantColumn(DataSource dataSource, String tenantId, String column)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement("select " + column + " from tenant where id = ?")) {
      query.setString(1, tenantId);
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next(), "no tenant " + tenantId);
        return rows.getString(1);
      }
    }
  }
}
