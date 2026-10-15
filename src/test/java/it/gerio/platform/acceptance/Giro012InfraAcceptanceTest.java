package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.path.json.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Giro 012: the Stripe variables travel to the server. Compose passes them to {@code app} as
 * optional values; deploy.sh refuses a malformed demo account before anything else; the secrets
 * travel only on stdin, never in the ssh command; remote.sh reads stdin line by line; smoke.sh
 * checks the webhook route. Never contacts a server, uses only invented values.
 */
class Giro012InfraAcceptanceTest {

  private static final Path INFRA = Path.of("infra");
  private static final String VALID_IMAGE =
      "ghcr.io/example/platform@sha256:" + "0123456789abcdef".repeat(4);
  private static final List<String> STRIPE_VARS =
      List.of("STRIPE_SECRET_KEY", "STRIPE_CONNECT_WEBHOOK_SECRET", "DEMO_STRIPE_ACCOUNT_ID");
  private static final List<String> SECRETS =
      List.of("GHCR_TOKEN", "STRIPE_SECRET_KEY", "STRIPE_CONNECT_WEBHOOK_SECRET");

  record Result(int exitCode, String stdout, String stderr) {}

  // --- compose ----------------------------------------------------------------------------------

  @Test
  void composePassesTheStripeVariablesToAppEmptyByDefault() throws Exception {
    JsonPath c = resolvedCompose(Map.of());
    Map<String, Object> env = c.getMap("services.app.environment");
    for (String name : STRIPE_VARS) {
      assertTrue(env.containsKey(name), "app does not get " + name + ": " + env.keySet());
      assertEquals("", String.valueOf(env.get(name)), name + " without a value");
    }
  }

  @Test
  void composePassesTheValuesOfTheEnvironment() throws Exception {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("STRIPE_SECRET_KEY", Giro012Profiles.TEST_KEY);
    values.put("STRIPE_CONNECT_WEBHOOK_SECRET", Giro012Profiles.WEBHOOK_SECRET);
    values.put("DEMO_STRIPE_ACCOUNT_ID", Giro012Profiles.ACCOUNT);
    JsonPath c = resolvedCompose(values);
    for (Map.Entry<String, String> e : values.entrySet()) {
      assertEquals(e.getValue(), c.getString("services.app.environment." + e.getKey()), e.getKey());
    }
  }

  // --- deploy.sh
  // ----------------------------------------------------------------------------------

  @Test
  void deployRefusesAMalformedDemoAccountWithCode2BeforeAnythingElse() throws Exception {
    for (String bad :
        List.of(
            "acct_1Glicine'; touch /tmp/giro012; '",
            "acct_1Glicine Demo",
            "acct_",
            "ACCT_1GlicineDemoTest0",
            "1GlicineDemoTest0")) {
      Result r = deploy(Map.of("DEMO_STRIPE_ACCOUNT_ID", bad));
      assertEquals(2, r.exitCode(), "\"" + bad + "\": " + r.stderr());
      assertTrue(r.stderr().contains("DEMO_STRIPE_ACCOUNT_ID"), "\"" + bad + "\": " + r.stderr());
      assertFalse(r.stderr().contains("missing variable"), "\"" + bad + "\": " + r.stderr());
    }
  }

  @Test
  void deployAcceptsAValidOrEmptyDemoAccountAndStopsOnMissingConfiguration() throws Exception {
    for (String good : List.of(Giro012Profiles.ACCOUNT, "")) {
      Result r = deploy(Map.of("DEMO_STRIPE_ACCOUNT_ID", good));
      assertEquals(3, r.exitCode(), "\"" + good + "\": " + r.stderr());
      assertTrue(r.stderr().contains("missing variable DEPLOY_HOST"), r.stderr());
    }
  }

  @Test
  void theSecretsTravelOnlyOnStdinNeverInTheSshCommand() throws Exception {
    String deploy = read(INFRA.resolve("deploy.sh"));
    for (String secret : SECRETS) {
      assertTrue(deploy.contains(secret), "deploy.sh does not send " + secret);
    }
    for (String line : logicalLines(deploy)) {
      int ssh = line.indexOf("ssh ");
      if (ssh < 0) {
        continue;
      }
      String command = line.substring(ssh);
      for (String secret : SECRETS) {
        assertFalse(command.contains(secret), secret + " in the ssh command: " + line);
      }
    }
  }

  // --- remote.sh and smoke.sh
  // ---------------------------------------------------------------------

  @Test
  void remoteReadsStdinLineByLineAndWritesTheStripeVariables() throws Exception {
    String remote = read(INFRA.resolve("remote.sh"));
    assertTrue(remote.contains("IFS= read -r"), "remote.sh does not read stdin line by line");
    for (String name : STRIPE_VARS) {
      assertTrue(remote.contains(name), "remote.sh does not handle " + name);
    }
  }

  @Test
  void smokeChecksTheWebhookRoute() throws Exception {
    String smoke = read(INFRA.resolve("smoke.sh"));
    assertTrue(smoke.contains("/webhooks/stripe/connect"), "smoke.sh does not check the webhook");
  }

  // --- helpers
  // ------------------------------------------------------------------------------------

  private static String read(Path file) throws IOException {
    assertTrue(Files.isRegularFile(file), "missing " + file);
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  /** Lines of a shell script with backslash continuations joined. */
  private static List<String> logicalLines(String script) {
    return List.of(script.replace("\\\n", " ").split("\n"));
  }

  private static JsonPath resolvedCompose(Map<String, String> env) throws Exception {
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
    assertEquals(0, r.exitCode(), "docker compose config: " + r.stderr());
    return JsonPath.from(r.stdout());
  }

  private static Result deploy(Map<String, String> env) throws Exception {
    Path script = INFRA.resolve("deploy.sh");
    assertTrue(Files.isExecutable(script), "not executable: " + script);
    return run(env, script.toString(), VALID_IMAGE);
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
                    || k.startsWith("SMOKE_")
                    || k.startsWith("STRIPE_")
                    || k.equals("DEMO_STRIPE_ACCOUNT_ID")
                    || k.equals("LAB_BASE_DOMAIN")
                    || k.equals("PLATFORM_BASE_DOMAIN")
                    || k.equals("APP_IMAGE")
                    || k.equals("IMAGE"));
    environment.putAll(env);
    Process process = pb.start();
    process.getOutputStream().close();
    var out = process.getInputStream();
    var err = process.getErrorStream();
    var stdout = new java.util.concurrent.CompletableFuture<String>();
    var stderr = new java.util.concurrent.CompletableFuture<String>();
    Thread.ofVirtual().start(() -> stdout.complete(readAll(out)));
    Thread.ofVirtual().start(() -> stderr.complete(readAll(err)));
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "timeout: " + String.join(" ", command));
    return new Result(
        process.exitValue(), stdout.get(10, TimeUnit.SECONDS), stderr.get(10, TimeUnit.SECONDS));
  }

  private static String readAll(java.io.InputStream in) {
    try {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }
}
