package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Giro 001 (goal T1): the files in infra/ that the "consegna" deploy job runs. Black box: checks
 * the files, the resolved compose and the scripts run without any secret. Never contacts a server.
 * Docker missing is a failure, not a skip.
 */
@QuarkusTest
class Giro001DeployAcceptanceTest {

  private static final Path INFRA = Path.of("infra");
  private static final List<String> SCRIPTS =
      List.of("deploy.sh", "smoke.sh", "rollback.sh", "remote.sh");
  private static final String DEMO_DOMAIN = "lab.example.invalid";
  private static final String VALID_IMAGE =
      "ghcr.io/example/platform@sha256:" + "0123456789abcdef".repeat(4);
  private static final Pattern DIGEST_IMAGE =
      Pattern.compile("ghcr\\.io/[^@\\s]+@sha256:[0-9a-f]{64}");

  record Result(int exitCode, String stdout, String stderr, Duration elapsed) {}

  // --- scripts: presence, syntax -------------------------------------------------------------

  @Test
  void scriptsExistAreExecutableAndParse() throws Exception {
    for (String name : SCRIPTS) {
      Path script = INFRA.resolve(name);
      assertTrue(Files.isRegularFile(script), "missing " + script);
      assertTrue(Files.isExecutable(script), "not executable: " + script);
      Result r = run(Duration.ofSeconds(10), Map.of(), "bash", "-n", script.toString());
      assertEquals(0, r.exitCode(), "bash -n " + script + ": " + r.stderr());
      assertFalse(
          Pattern.compile("(?m)^\\s*set\\s+-[a-zA-Z]*x").matcher(read(script)).find(),
          "set -x is forbidden in " + script);
    }
  }

  // --- compose --------------------------------------------------------------------------------

  @Test
  void composeIsValidWithTheExampleEnv() throws Exception {
    Result r = compose("config", "-q");
    assertEquals(0, r.exitCode(), "docker compose config -q: " + r.stderr());
  }

  @Test
  void resolvedComposeHasProjectNameServicesAndOnlyCaddyPublishesPorts() throws Exception {
    JsonPath c = resolvedCompose();
    assertEquals("gerio-lab", c.getString("name"));
    Map<String, Object> services = c.getMap("services");
    assertEquals(Set.of("app", "caddy", "db"), new TreeSet<>(services.keySet()));
    for (String service : services.keySet()) {
      List<Object> ports = c.getList("services." + service + ".ports");
      boolean hasPorts = ports != null && !ports.isEmpty();
      assertEquals("caddy".equals(service), hasPorts, "ports on service " + service);
    }
  }

  @Test
  void everyImageIsPinnedByDigestOrExactTag() throws Exception {
    JsonPath c = resolvedCompose();
    Map<String, Object> services = c.getMap("services");
    for (String service : services.keySet()) {
      String image = c.getString("services." + service + ".image");
      assertNotNull(image, "no image for " + service);
      assertTrue(isPinned(image), "image not pinned for " + service + ": " + image);
    }
    assertEquals(exampleEnvValue("APP_IMAGE"), c.getString("services.app.image"));
  }

  @Test
  void appGetsDomainDatasourceHealthcheckAndWaitsForHealthyDb() throws Exception {
    JsonPath c = resolvedCompose();
    assertEquals(DEMO_DOMAIN, c.getString("services.app.environment.PLATFORM_BASE_DOMAIN"));
    assertEquals(
        "jdbc:postgresql://db:5432/platform",
        c.getString("services.app.environment.QUARKUS_DATASOURCE_JDBC_URL"));
    assertNotNull(c.get("services.app.healthcheck"), "app has no healthcheck");
    assertEquals("service_healthy", c.getString("services.app.depends_on.db.condition"));
  }

  @Test
  void caddyGetsTheLabDomainAndDbHasAHealthcheck() throws Exception {
    JsonPath c = resolvedCompose();
    assertEquals(DEMO_DOMAIN, c.getString("services.caddy.environment.LAB_BASE_DOMAIN"));
    assertNotNull(c.get("services.db.healthcheck"), "db has no healthcheck");
  }

  @Test
  void aNetworkHasTheFixedSubnet() throws Exception {
    JsonPath c = resolvedCompose();
    Map<String, Object> networks = c.getMap("networks");
    assertNotNull(networks, "no networks");
    List<String> subnets = new ArrayList<>();
    for (String network : networks.keySet()) {
      List<String> s = c.getList("networks.'" + network + "'.ipam.config.subnet");
      if (s != null) {
        subnets.addAll(s);
      }
    }
    assertTrue(subnets.contains("172.30.0.0/24"), "subnets: " + subnets);
  }

  @Test
  void envExampleHasAFullDigestAndBothDomains() throws Exception {
    String image = exampleEnvValue("APP_IMAGE");
    assertNotNull(image, "APP_IMAGE missing in env.example");
    assertTrue(DIGEST_IMAGE.matcher(image).matches(), "APP_IMAGE is not a full digest: " + image);
    assertNotNull(exampleEnvValue("PLATFORM_BASE_DOMAIN"), "PLATFORM_BASE_DOMAIN missing");
    assertNotNull(exampleEnvValue("LAB_BASE_DOMAIN"), "LAB_BASE_DOMAIN missing");
  }

  // --- Caddyfile and server-side script --------------------------------------------------------

  @Test
  void caddyfileHidesManagementEndpointsAndSetsHsts() throws Exception {
    String caddyfile = read(INFRA.resolve("Caddyfile"));
    for (String expected :
        List.of(
            "{$LAB_BASE_DOMAIN}",
            "/internal/*",
            "/q/*",
            "respond @internal 404",
            "health_uri /q/health/ready",
            "Strict-Transport-Security")) {
      assertTrue(caddyfile.contains(expected), "Caddyfile lacks " + expected);
    }
  }

  @Test
  void remoteScriptLocksReloadsCaddyWaitsAndLogsOut() throws Exception {
    String remote = read(INFRA.resolve("remote.sh"));
    for (String expected :
        List.of(
            "flock",
            "caddy validate",
            "caddy reload",
            "--wait",
            "--password-stdin",
            "docker logout",
            "previous")) {
      assertTrue(remote.contains(expected), "remote.sh lacks " + expected);
    }
  }

  // --- scripts run without secrets ------------------------------------------------------------

  @Test
  void deployRejectsAMissingArgumentWithCode2() throws Exception {
    Result r = script("deploy.sh", Map.of());
    assertEquals(2, r.exitCode(), r.stderr());
    assertTrue(r.stderr().contains("invalid image reference"), r.stderr());
    assertTrue(r.elapsed().compareTo(Duration.ofSeconds(5)) < 0, "too slow: " + r.elapsed());
  }

  @Test
  void deployRejectsATagWithCode2() throws Exception {
    Result r = script("deploy.sh", Map.of(), "ghcr.io/example/platform:latest");
    assertEquals(2, r.exitCode(), r.stderr());
    assertTrue(r.stderr().contains("invalid image reference"), r.stderr());
    assertTrue(r.elapsed().compareTo(Duration.ofSeconds(5)) < 0, "too slow: " + r.elapsed());
  }

  @Test
  void deployAcceptsADigestAndThenStopsOnMissingConfigurationWithCode3() throws Exception {
    Result r = script("deploy.sh", Map.of(), VALID_IMAGE);
    assertEquals(3, r.exitCode(), r.stderr());
    assertTrue(r.stderr().contains("missing variable DEPLOY_HOST"), r.stderr());
    assertFalse(r.stderr().contains("invalid image reference"), r.stderr());
    assertTrue(r.elapsed().compareTo(Duration.ofSeconds(5)) < 0, "too slow: " + r.elapsed());
  }

  @Test
  void rollbackStopsOnMissingConfigurationWithCode3() throws Exception {
    Result r = script("rollback.sh", Map.of());
    assertEquals(3, r.exitCode(), r.stderr());
    assertTrue(r.stderr().contains("missing variable DEPLOY_HOST"), r.stderr());
  }

  @Test
  void smokeWithoutDomainExitsWithCode3() throws Exception {
    Result r = script("smoke.sh", Map.of());
    assertEquals(3, r.exitCode(), r.stderr());
  }

  @Test
  void smokeAgainstAnUnreachableDomainFailsWithCode1Quickly() throws Exception {
    Result r =
        script(
            "smoke.sh",
            Map.of("LAB_BASE_DOMAIN", DEMO_DOMAIN, "SMOKE_ATTEMPTS", "1", "SMOKE_DELAY", "0"));
    assertEquals(1, r.exitCode(), r.stderr());
    assertTrue(r.elapsed().compareTo(Duration.ofSeconds(20)) < 0, "too slow: " + r.elapsed());
  }

  @Test
  void noSecretLooksCommittedInInfra() throws Exception {
    List<String> forbidden = List.of("BEGIN OPENSSH", "BEGIN RSA", "ghp_", "sk_live_", "sk_test_");
    try (Stream<Path> files = Files.walk(INFRA)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String content = read(file);
        for (String f : forbidden) {
          assertFalse(content.contains(f), file + " contains " + f);
        }
      }
    }
  }

  // --- app guard (already green, must stay green) ---------------------------------------------

  @Test
  void landingAnswersOnLocalhost() {
    given()
        .header("Host", "localhost")
        .when()
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("Negozi locali online"));
  }

  @Test
  void readinessIsUp() {
    given().when().get("/q/health/ready").then().statusCode(200).body(containsString("UP"));
  }

  // --- helpers --------------------------------------------------------------------------------

  private static boolean isPinned(String image) {
    if (image.contains("@sha256:")) {
      return Pattern.compile("@sha256:[0-9a-f]{64}$").matcher(image).find();
    }
    String last = image.substring(image.lastIndexOf('/') + 1);
    int colon = last.indexOf(':');
    if (colon < 0) {
      return false;
    }
    String tag = last.substring(colon + 1);
    return !tag.isBlank() && !"latest".equals(tag);
  }

  private static String exampleEnvValue(String key) throws IOException {
    assertTrue(Files.isRegularFile(INFRA.resolve("env.example")), "missing env.example");
    for (String line : Files.readAllLines(INFRA.resolve("env.example"), StandardCharsets.UTF_8)) {
      String trimmed = line.strip();
      if (trimmed.startsWith(key + "=")) {
        return trimmed.substring(key.length() + 1).replace("\"", "").replace("'", "");
      }
    }
    return null;
  }

  private static JsonPath resolvedCompose() throws Exception {
    Result r = compose("config", "--format", "json");
    assertEquals(0, r.exitCode(), "docker compose config: " + r.stderr());
    return JsonPath.from(r.stdout());
  }

  private static Result compose(String... args) throws Exception {
    assertTrue(Files.isRegularFile(INFRA.resolve("docker-compose.yml")), "missing compose file");
    assertTrue(Files.isRegularFile(INFRA.resolve("env.example")), "missing env.example");
    List<String> command = new ArrayList<>();
    command.addAll(
        List.of(
            "docker",
            "compose",
            "-f",
            INFRA.resolve("docker-compose.yml").toString(),
            "--env-file",
            INFRA.resolve("env.example").toString()));
    command.addAll(List.of(args));
    return run(Duration.ofSeconds(60), Map.of(), command.toArray(String[]::new));
  }

  private static Result script(String name, Map<String, String> env, String... args)
      throws Exception {
    Path script = INFRA.resolve(name);
    assertTrue(Files.isExecutable(script), "not executable: " + script);
    List<String> command = new ArrayList<>();
    command.add(script.toString());
    command.addAll(List.of(args));
    return run(Duration.ofSeconds(30), env, command.toArray(String[]::new));
  }

  private static Result run(Duration timeout, Map<String, String> env, String... command)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    Map<String, String> environment = pb.environment();
    environment
        .keySet()
        .removeIf(
            k ->
                k.startsWith("DEPLOY_")
                    || k.startsWith("GHCR_")
                    || k.startsWith("SMOKE_")
                    || k.equals("LAB_BASE_DOMAIN")
                    || k.equals("PLATFORM_BASE_DOMAIN")
                    || k.equals("APP_IMAGE")
                    || k.equals("IMAGE"));
    environment.putAll(env);
    Path out = Files.createTempFile("giro001-out", ".txt");
    Path err = Files.createTempFile("giro001-err", ".txt");
    pb.redirectOutput(out.toFile());
    pb.redirectError(err.toFile());
    pb.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
    long start = System.nanoTime();
    Process process = pb.start();
    boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
    if (!finished) {
      process.destroyForcibly();
    }
    try {
      String stdout = Files.readString(out, StandardCharsets.UTF_8);
      String stderr = Files.readString(err, StandardCharsets.UTF_8);
      assertTrue(finished, "timed out after " + timeout + ": " + String.join(" ", command));
      return new Result(process.exitValue(), stdout, stderr, elapsed);
    } finally {
      Files.deleteIfExists(out);
      Files.deleteIfExists(err);
    }
  }

  private static String read(Path file) throws IOException {
    assertTrue(Files.isRegularFile(file), "missing " + file);
    return Files.readString(file, StandardCharsets.UTF_8);
  }
}
