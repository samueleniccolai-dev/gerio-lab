package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Giro 015: one new dependency only ({@code quarkus-mailer}, human answer «A»), no scheduler
 * extension; in production the mail is off until a provider exists and the mock mailer, which logs
 * recipients and bodies, is never on (docs/lab/giri/015-piano.md).
 */
class Giro015MailConfigAcceptanceTest {

  private static final Path POM = Path.of("pom.xml");
  private static final Path PROPERTIES =
      Path.of("src", "main", "resources", "application.properties");

  private static List<String> properties() throws IOException {
    return Files.readAllLines(PROPERTIES, StandardCharsets.UTF_8).stream()
        .map(String::strip)
        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
        .toList();
  }

  @Test
  void thePomHasTheMailerAndNoScheduler() throws IOException {
    String pom = Files.readString(POM, StandardCharsets.UTF_8);
    assertTrue(
        pom.contains("<artifactId>quarkus-mailer</artifactId>"), "quarkus-mailer missing in pom");
    assertFalse(pom.contains("quarkus-scheduler"), "quarkus-scheduler is not approved");
    assertFalse(pom.contains("quarkus-quartz"), "quarkus-quartz is not approved");
  }

  @Test
  void mailIsOffByDefaultAndTheMockMailerIsNeverOnInProduction() throws IOException {
    List<String> lines = properties();
    List<String> enabled =
        lines.stream().filter(line -> line.startsWith("app.mail.enabled=")).toList();
    assertEquals(1, enabled.size(), "exactly one unprofiled app.mail.enabled line: " + enabled);
    String value = enabled.get(0).substring("app.mail.enabled=".length());
    assertTrue(
        value.equals("false") || value.matches("\\$\\{[A-Z_]+:false}"),
        "app.mail.enabled must default to false: " + value);
    assertTrue(
        lines.stream().noneMatch(line -> line.startsWith("%prod.app.mail.enabled")),
        "%prod must not switch the mail on");
    assertTrue(
        lines.stream()
            .noneMatch(
                line ->
                    line.matches("(%prod\\.)?quarkus\\.mailer\\.mock=.*")
                        && !line.endsWith("=false")),
        "quarkus.mailer.mock must not be on (or configurable) outside dev and test");
  }
}
