package it.gerio.platform.acceptance;

import static it.gerio.platform.acceptance.Giro005DemoData.DEMO_ID;
import static it.gerio.platform.acceptance.Giro017Data.CAPANNORI;
import static it.gerio.platform.acceptance.Giro017Data.LUCCA;
import static it.gerio.platform.acceptance.Giro017Data.VERSILIA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giro 017 (goal T7): the demo seed creates the three zones of the plan and the settings of the
 * shop, and running it again changes nothing (docs/lab/giri/017-piano.md).
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class Giro017DemoZonesSeedAcceptanceTest {

  @Inject DataSource dataSource;

  private Giro017Data data;

  @BeforeEach
  void setUp() throws Exception {
    data = new Giro017Data(dataSource);
  }

  @AfterEach
  void tearDown() throws SQLException {
    data.deleteAll();
  }

  @Test
  void theSeedTwiceGivesThreeZonesAndTheSettings() throws Exception {
    new Giro005DemoData(dataSource).runSeed();

    List<Map<String, Object>> zones = data.zones(DEMO_ID);
    assertEquals(3, zones.size(), "zones of the demo after the seed ran twice: " + zones);
    assertZone(zones.get(0), LUCCA, "55100", "Lucca", 500, 2500, true);
    assertZone(zones.get(1), CAPANNORI, "55012,55016", "Capannori,Porcari", 900, 3500, true);
    assertZone(zones.get(2), VERSILIA, "55049", "Viareggio", 1500, 5000, false);

    assertEquals("12:00", data.setting(DEMO_ID, "orderCutoff"), "settings.orderCutoff");
    assertEquals("+393330000000", data.setting(DEMO_ID, "whatsapp"), "settings.whatsapp");
  }

  @Test
  void aNegativeFeeOrMinimumIsRefusedByTheDatabase() throws SQLException {
    data.requireZones();
    assertThrows(
        SQLException.class,
        () -> data.insertZone(DEMO_ID, "Zona negativa", "55000", "Nessuno", -1, 0),
        "a zone with a negative fee");
    assertThrows(
        SQLException.class,
        () -> data.insertZone(DEMO_ID, "Minimo negativo", "55000", "Nessuno", 0, -1),
        "a zone with a negative minimum");
  }

  private static void assertZone(
      Map<String, Object> zone,
      String name,
      String postalCodes,
      String municipalities,
      long fee,
      long minimum,
      boolean active) {
    assertEquals(name, zone.get("name"), "zone name");
    assertEquals(postalCodes, zone.get("postal_codes"), name + ": postal codes");
    assertEquals(municipalities, zone.get("municipalities"), name + ": municipalities");
    assertEquals(fee, ((Number) zone.get("fee_cents")).longValue(), name + ": fee");
    assertEquals(minimum, ((Number) zone.get("min_order_cents")).longValue(), name + ": minimum");
    assertEquals(active, zone.get("active"), name + ": active");
  }
}
