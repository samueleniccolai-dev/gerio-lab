package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Giro 005: the demo seed is switched on by app.demo.seed in dev and prod only, and no Flyway
 * location changes (the seed is not a migration).
 */
class Giro005DemoSeedConfigAcceptanceTest {

  private static final Path PROPERTIES =
      Path.of("src", "main", "resources", "application.properties");

  private static List<String> lines() throws IOException {
    return Files.readAllLines(PROPERTIES, StandardCharsets.UTF_8).stream()
        .map(String::strip)
        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
        .toList();
  }

  @Test
  void theSeedIsOffByDefaultAndOnInDevAndProd() throws IOException {
    List<String> lines = lines();
    assertTrue(lines.contains("app.demo.seed=false"), "missing app.demo.seed=false");
    assertTrue(lines.contains("%dev.app.demo.seed=true"), "missing %dev.app.demo.seed=true");
    assertTrue(lines.contains("%prod.app.demo.seed=true"), "missing %prod.app.demo.seed=true");
    assertEquals(
        List.of("app.demo.seed=false", "%dev.app.demo.seed=true", "%prod.app.demo.seed=true"),
        lines.stream().filter(line -> line.contains("app.demo.seed")).toList(),
        "app.demo.seed must be set exactly in these three lines");
  }

  @Test
  void theFlywayLocationsAreUnchanged() throws IOException {
    assertEquals(
        List.of("%test.quarkus.flyway.locations=db/migration,db/test-migration"),
        lines().stream().filter(line -> line.contains("flyway.locations")).toList());
  }
}
