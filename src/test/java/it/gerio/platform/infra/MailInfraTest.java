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
    // Host and from must never be empty: SmallRye does not apply the default of an empty variable.
    for (JsonPath j : List.of(c, compose(Map.of("GERIO_SMTP_HOST", "", "GERIO_MAIL_FROM", "")))) {
      assertEquals("localhost", j.getString("services.app.environment.GERIO_SMTP_HOST"));
      assertEquals(
          "ordini@lab.example.invalid", j.getString("services.app.environment.GERIO_MAIL_FROM"));
    }
    for (String n : List.of("GERIO_SMTP_USER", "GERIO_SMTP_PASSWORD")) {
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

  private static final List<String> SECRETS =
      List.of(
          "tok-ghcr-0001", "sk-stripe-0002", "whsec-0003", "centinaio-u-0004", "centinaio-p-0005");

  @Test
  void deployKeepsSecretsOutOfSshArgumentsAndSendsThemInOrderOnStdin() throws Exception {
    Path dir = Files.createTempDirectory("mail-deploy");
    Path bin = Files.createDirectory(dir.resolve("bin"));
    for (String tool : List.of("ssh", "scp")) {
      Path f = bin.resolve(tool);
      Files.writeString(
          f,
          "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" >> \""
              + dir
              + "/"
              + tool
              + ".argv\"\ncat >> \""
              + dir
              + "/"
              + tool
              + ".stdin\"\n");
      f.toFile().setExecutable(true);
    }
    Map<String, String> env = new java.util.HashMap<>();
    env.put("PATH", bin + ":" + System.getenv("PATH"));
    env.put("DEPLOY_HOST", "h.example.invalid");
    env.put("DEPLOY_USER", "deploy");
    env.put("DEPLOY_SSH_KEY", "fake-key");
    env.put("DEPLOY_KNOWN_HOSTS", "fake-known-hosts");
    env.put("LAB_BASE_DOMAIN", "lab.example.invalid");
    env.put("GHCR_USER", "ghuser");
    env.put("GHCR_TOKEN", SECRETS.get(0));
    env.put("STRIPE_SECRET_KEY", SECRETS.get(1));
    env.put("STRIPE_CONNECT_WEBHOOK_SECRET", SECRETS.get(2));
    env.put("GERIO_SMTP_USER", SECRETS.get(3));
    env.put("GERIO_SMTP_PASSWORD", SECRETS.get(4));
    env.put("GERIO_SMTP_PORT", "587");
    Result r = run(env, "infra/deploy.sh", IMAGE);
    assertEquals(0, r.exitCode(), r.stderr());
    String argv =
        Files.readString(dir.resolve("ssh.argv")) + Files.readString(dir.resolve("scp.argv"));
    for (String secret : SECRETS) {
      assertFalse(argv.contains(secret), "secret value in argv: " + secret);
    }
    assertFalse(argv.contains("GERIO_SMTP_"), "variable name in argv");
    assertEquals(SECRETS, Files.readAllLines(dir.resolve("ssh.stdin")));
  }

  @Test
  void remoteReadsUserBeforePasswordAndValidatesThePort() throws Exception {
    Path root = Files.createTempDirectory("mail-remote");
    Path bin = Files.createDirectory(root.resolve("bin"));
    Path docker = bin.resolve("docker");
    Files.writeString(
        docker, "#!/usr/bin/env bash\necho '" + IMAGE.substring(IMAGE.indexOf('@') + 1) + "'\n");
    docker.toFile().setExecutable(true);
    Path flock = bin.resolve("flock");
    Files.writeString(flock, "#!/usr/bin/env bash\nexit 0\n");
    flock.toFile().setExecutable(true);
    Path script = root.resolve("remote.sh");
    Files.writeString(
        script, read("remote.sh").replace("ROOT=/opt/gerio-lab", "ROOT=\"" + root + "\""));
    Files.writeString(root.resolve(".env"), "DB_PASSWORD=x\n");
    Files.createDirectory(root.resolve("incoming"));
    for (String f : List.of("docker-compose.yml", "Caddyfile", "remote.sh")) {
      Files.writeString(root.resolve("incoming").resolve(f), "# fake\n");
    }
    String path = bin + ":" + System.getenv("PATH");
    String stdin = "ghcr-t\nstripe-k\nwhsec\nthe-user-0006\nthe-pass-0007\n";

    Result bad = runRemote(script, path, stdin, "70000");
    assertEquals(2, bad.exitCode(), bad.stderr());
    assertTrue(bad.stderr().contains("GERIO_SMTP_PORT"), bad.stderr());
    assertEquals(2, runRemote(script, path, stdin, "0").exitCode());

    Result ok = runRemote(script, path, stdin, "2525");
    assertEquals(0, ok.exitCode(), ok.stderr());
    List<String> env = Files.readAllLines(root.resolve(".env"));
    assertTrue(env.contains("GERIO_SMTP_USER=the-user-0006"), env.toString());
    assertTrue(env.contains("GERIO_SMTP_PASSWORD=the-pass-0007"), env.toString());
    assertTrue(env.contains("GERIO_SMTP_PORT=2525"), env.toString());
  }

  private static Result runRemote(Path script, String path, String stdin, String port)
      throws Exception {
    ProcessBuilder pb =
        new ProcessBuilder(
            "bash",
            script.toString(),
            "deploy",
            IMAGE,
            "lab.example.invalid",
            "ghuser",
            "",
            "smtp.example.invalid",
            port,
            "ordini@example.invalid",
            "true");
    pb.environment().put("PATH", path);
    Process p = pb.start();
    p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
    p.getOutputStream().close();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(p.waitFor(60, TimeUnit.SECONDS), "timeout");
    return new Result(p.exitValue(), err, out);
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
