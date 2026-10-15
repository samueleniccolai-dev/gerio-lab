package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Giro 012: reads the application log file of the running profile (path from {@code
 * quarkus.log.file.path}, lines {@code LEVEL category message}, see {@link Giro012Profiles}).
 */
final class Giro012Log {

  private Giro012Log() {}

  static Path path() {
    String path =
        ConfigProvider.getConfig()
            .getOptionalValue("quarkus.log.file.path", String.class)
            .orElseThrow(() -> new AssertionError("quarkus.log.file.path is not set"));
    return Path.of(path);
  }

  /** All lines written so far (empty if nothing was written yet). */
  static List<String> lines() throws IOException {
    Path path = path();
    if (!Files.exists(path)) {
      return List.of();
    }
    return Files.readAllLines(path, StandardCharsets.UTF_8);
  }

  /** Number of lines written so far: a mark for {@link #since(int)}. */
  static int mark() throws IOException {
    return lines().size();
  }

  static List<String> since(int mark) throws IOException {
    List<String> all = lines();
    return all.subList(Math.min(mark, all.size()), all.size());
  }

  static boolean isLevel(String line, String... levels) {
    for (String level : levels) {
      if (line.startsWith(level + " ")) {
        return true;
      }
    }
    return false;
  }

  static boolean mentions(String line, String text) {
    return line.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
  }

  /** No line of the whole log contains the invented keys (nor their common part). */
  static void assertNoKey() throws IOException {
    for (String line : lines()) {
      assertFalse(line.contains(Giro012Profiles.KEY_MARK), "the log shows a key: " + line);
    }
  }

  /** At least one line at one of the levels mentions the text (case-insensitive). */
  static void assertLine(List<String> lines, String text, String what, String... levels) {
    assertTrue(
        lines.stream().anyMatch(l -> isLevel(l, levels) && mentions(l, text)),
        what
            + ": no "
            + String.join("/", levels)
            + " line mentioning \""
            + text
            + "\" in "
            + lines);
  }
}
