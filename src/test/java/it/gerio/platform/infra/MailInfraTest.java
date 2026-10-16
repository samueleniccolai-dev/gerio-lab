package it.gerio.platform.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.path.json.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Mail variables in infra/: deploy.sh validation, remote.sh handling, compose defaults. */
class MailInfraTest {

  private static final Path INFRA = Path.of("infra");
  private static final String IMAGE =
      "ghcr.io/example/platform@sha256:" + "0123456789abcdef".repeat(4);

  record Result(int exitCode, String stderr, String stdout) {}

  @Test
  void deployRefusesInvalidMailValuesWithCode2() throws Exception {
    List<Map<String, String>> bad =
        List.of(
            Map.of("GERIO_SMTP_PORT", "abc"),
            Map.of("GERIO_SMTP_PORT", "0"),
            Map.of("GERIO_SMTP_PORT", "70000"),
            Map.of("GERIO_SMTP_PORT", "58 7"),
            Map.of("GERIO_MAIL_ENABLED", "yes"),
            Map.of("GERIO_MAIL_ENABLED", "TRUE"),
            Map.of("GERIO_SMTP_HOST", "smtp.example.invalid; touch /tmp/x"),
            Map.of("GERIO_SMTP_HOST", "smtp'.example.invalid"),
            Map.of("GERIO_SMTP_HOST", "smtp.example.invalid\nx"),
            Map.of("GERIO_MAIL_FROM", "ordini @example.invalid"),
            Map.of("GERIO_MAIL_FROM", "\"ordini@example.invalid\""));
    for (Map<String, String> env : bad) {
      Result r = run(env, "infra/deploy.sh", IMAGE);
      String name = env.keySet().iterator().next();
      assertEquals(2, r.exitCode(), env + ": " + r.stderr());
      assertTrue(r.stderr().contains(name), env + ": " + r.stderr());
      assertFalse(r.stderr().contains("missing variable"), env + ": " + r.stderr());
    }
  }

  @Test
  void deployAcceptsValidOrEmptyMailValuesAndStopsOnMissingConfiguration() throws Exception {
    List<Map<String, String>> good =
        List.of(
            Map.of(),
            Map.of(
                "GERIO_SMTP_HOST", "smtp-relay.brevo.com",
                "GERIO_SMTP_PORT", "587",
                "GERIO_MAIL_FROM", "ordini@example.invalid",
                "GERIO_MAIL_ENABLED", "true"),
            Map.of("GERIO_SMTP_HOST", "", "GERIO_SMTP_PORT", "", "GERIO_MAIL_ENABLED", ""));
    for (Map<String, String> env : good) {
      Result r = run(env, "infra/deploy.sh", IMAGE);
      assertEquals(3, r.exitCode(), env + ": " + r.stderr());
      assertTrue(r.stderr().contains("missing variable DEPLOY_HOST"), r.stderr());
    }
  }

  @Test
  void smtpCredentialsTravelOnStdinAndRemoteWritesEverythingOnlyWhenSet() throws Exception {
    String deploy = read("deploy.sh");
    String remote = read("remote.sh");
    List<String> names =
        List.of(
            "GERIO_SMTP_USER",
            "GERIO_SMTP_PASSWORD",
            "GERIO_SMTP_HOST",
            "GERIO_SMTP_PORT",
            "GERIO_MAIL_FROM",
            "GERIO_MAIL_ENABLED");
    for (String n : names) {
      assertTrue(deploy.contains(n), "deploy.sh: " + n);
      assertTrue(remote.contains("set_env " + n), "remote.sh does not write " + n);
    }
    for (String line : deploy.replace("\\\n", " ").split("\n")) {
      int ssh = line.indexOf("ssh ");
      if (ssh >= 0) {
        String command = line.substring(ssh);
        assertFalse(command.contains("GERIO_SMTP_USER"), line);
        assertFalse(command.contains("GERIO_SMTP_PASSWORD"), line);
      }
    }
  }

  @Test
  void composePassesTheMailVariablesWithSafeDefaults() throws Exception {
    JsonPath c = compose(Map.of());
    assertEquals("false", c.getString("services.app.environment.GERIO_MAIL_ENABLED"));
    assertEquals("587", c.getString("services.app.environment.GERIO_SMTP_PORT"));
    for (String n :
        List.of("GERIO_SMTP_HOST", "GERIO_SMTP_USER", "GERIO_SMTP_PASSWORD", "GERIO_MAIL_FROM")) {
      assertEquals("", c.getString("services.app.environment." + n), n);
    }
    JsonPath set =
        compose(
            Map.of(
                "GERIO_MAIL_ENABLED", "true",
                "GERIO_SMTP_PORT", "2525",
                "GERIO_SMTP_HOST", "smtp-relay.brevo.com",
                "GERIO_MAIL_FROM", "ordini@example.invalid"));
    assertEquals("true", set.getString("services.app.environment.GERIO_MAIL_ENABLED"));
    assertEquals("2525", set.getString("services.app.environment.GERIO_SMTP_PORT"));
    assertEquals("smtp-relay.brevo.com", set.getString("services.app.environment.GERIO_SMTP_HOST"));
    assertEquals(
        "ordini@example.invalid", set.getString("services.app.environment.GERIO_MAIL_FROM"));
  }

  private static JsonPath compose(Map<String, String> env) throws Exception {
    Result r =
        run(
            env,
            "docker",
            "compose",
            "-f",
            INFRA.resolve("docker-compose.yml").toString(),
            "--env-file",
            INFRA.resolve("env.example").toString(),
            "config",
            "--format",
            "json");
    Assumptions.assumeTrue(r.exitCode() != 127, "docker not available");
    assertEquals(0, r.exitCode(), r.stderr());
    return JsonPath.from(r.stdout());
  }

  private static String read(String file) throws Exception {
    return Files.readString(INFRA.resolve(file), StandardCharsets.UTF_8);
  }

  private static Result run(Map<String, String> env, String... command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(List.of(command)));
    Map<String, String> environment = pb.environment();
    environment
        .keySet()
        .removeIf(
            k ->
                k.startsWith("DEPLOY_")
                    || k.startsWith("GHCR_")
                    || k.startsWith("GERIO_")
                    || k.startsWith("STRIPE_")
                    || k.equals("DEMO_STRIPE_ACCOUNT_ID")
                    || k.equals("LAB_BASE_DOMAIN")
                    || k.equals("PLATFORM_BASE_DOMAIN")
                    || k.equals("APP_IMAGE"));
    environment.putAll(env);
    Process p;
    try {
      p = pb.start();
    } catch (java.io.IOException e) {
      return new Result(127, e.getMessage(), "");
    }
    p.getOutputStream().close();
    var out = new java.util.concurrent.CompletableFuture<String>();
    var err = new java.util.concurrent.CompletableFuture<String>();
    Thread.ofVirtual().start(() -> out.complete(slurp(p.getInputStream())));
    Thread.ofVirtual().start(() -> err.complete(slurp(p.getErrorStream())));
    assertTrue(p.waitFor(60, TimeUnit.SECONDS), "timeout");
    return new Result(p.exitValue(), err.get(10, TimeUnit.SECONDS), out.get(10, TimeUnit.SECONDS));
  }

  private static String slurp(java.io.InputStream in) {
    try {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      return "";
    }
  }
}
