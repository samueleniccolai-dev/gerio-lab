package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Giro 013: runs the infra scripts with fake commands first in PATH and a clean environment (no
 * deploy, smoke or Stripe variables inherited from the machine). Never contacts a server.
 */
final class Giro013Shell {

  record Result(int exitCode, String stdout, String stderr) {}

  private Giro013Shell() {}

  static void install(Path dir, String name, String content) throws Exception {
    Path file = dir.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
  }

  static String pathWith(Path dir) {
    return dir.toAbsolutePath() + ":" + System.getenv().getOrDefault("PATH", "/usr/bin:/bin");
  }

  static Result run(Duration timeout, Map<String, String> env, String... command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    Map<String, String> environment = pb.environment();
    environment
        .keySet()
        .removeIf(
            k ->
                k.startsWith("DEPLOY_")
                    || k.startsWith("GHCR_")
                    || k.startsWith("SMOKE_")
                    || k.startsWith("STRIPE_")
                    || k.startsWith("PROVA_")
                    || k.startsWith("DEMO_")
                    || k.equals("LAB_BASE_DOMAIN")
                    || k.equals("PLATFORM_BASE_DOMAIN")
                    || k.equals("APP_IMAGE")
                    || k.equals("IMAGE"));
    environment.putAll(env);
    Path out = Files.createTempFile("giro013-out", ".txt");
    Path err = Files.createTempFile("giro013-err", ".txt");
    pb.redirectOutput(out.toFile());
    pb.redirectError(err.toFile());
    pb.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
    Process process = pb.start();
    boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
    }
    try {
      String stdout = Files.readString(out, StandardCharsets.UTF_8);
      String stderr = Files.readString(err, StandardCharsets.UTF_8);
      assertTrue(finished, "timed out after " + timeout + ": " + String.join(" ", command));
      return new Result(process.exitValue(), stdout, stderr);
    } finally {
      Files.deleteIfExists(out);
      Files.deleteIfExists(err);
    }
  }
}
