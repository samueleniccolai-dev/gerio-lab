package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro005DemoData.MARCO_ID;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gerio.platform.tenant.TestShops;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Giro 008 test data: the demo seed plus Marco's shop (Giro005DemoData), the ids of the variants
 * the plan names, and a cleanup that empties the cart tables (if they exist) before the shops.
 */
final class Giro008CartData {

  static final String DEMO = "demo.localhost";
  static final String MARCO = "fiori-di-marco.localhost";
  static final String PLATFORM = "localhost";

  private final DataSource dataSource;

  final long piccolo;
  final long medio;
  final long grande;
  final long extra;
  final long orchid;
  final long advent;
  final long poinsettia;
  final long marco;

  Giro008CartData(DataSource dataSource) throws IOException, SQLException {
    this.dataSource = dataSource;
    deleteAll();
    Giro005DemoData seed = new Giro005DemoData(dataSource);
    seed.runSeed();
    seed.insertMarco();
    piccolo = variant(DEMO_ID, "bouquet-di-stagione", "Piccolo");
    medio = variant(DEMO_ID, "bouquet-di-stagione", "Medio");
    grande = variant(DEMO_ID, "bouquet-di-stagione", "Grande");
    extra = variant(DEMO_ID, "bouquet-di-stagione", "Extra");
    orchid = variant(DEMO_ID, "orchidea-bianca", "Vaso 12 cm");
    advent = variant(DEMO_ID, "corona-avvento", "Unica");
    poinsettia = variant(DEMO_ID, "stella-di-natale", "Vaso 14 cm");
    marco = variant(MARCO_ID, "bouquet-di-stagione", "Unico");
  }

  /** Empties cart_item and cart when the tables exist, then all shops. */
  void deleteAll() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "do $$ begin"
              + " if to_regclass('cart_item') is not null then execute 'delete from cart_item';"
              + " end if;"
              + " if to_regclass('cart') is not null then execute 'delete from cart'; end if;"
              + " end $$");
    }
    new TestShops(dataSource).deleteAll();
  }

  private long variant(String tenantId, String productSlug, String variantName)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "select v.id from product_variant v join product p on p.id = v.product_id"
                    + " where v.tenant_id = ? and p.slug = ? and v.name = ?")) {
      query.setString(1, tenantId);
      query.setString(2, productSlug);
      query.setString(3, variantName);
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next(), "no variant " + productSlug + " / " + variantName);
        return rows.getLong(1);
      }
    }
  }
}
