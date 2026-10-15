package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.path.json.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Giro 006 (goal T3, last part): the demo shop answers on demo.&lt;LAB_BASE_DOMAIN&gt; behind
 * Caddy, and smoke.sh checks it from outside. Black box: the Caddyfile is adapted by the pinned
 * Caddy image (stdin, no mount), smoke.sh runs against a fake curl placed first in PATH. Never
 * contacts a server. Docker missing is a failure, not a skip.
 */
class Giro006DemoHostAcceptanceTest {

  private static final Path INFRA = Path.of("infra");
  private static final String CADDY_IMAGE = "caddy:2.11.7";
  private static final String BASE = "lab.example.invalid";
  private static final String DEMO = "demo." + BASE;

  private static final String CATALOG = "https://" + DEMO + "/catalogo";
  private static final String PRODUCT = "https://" + DEMO + "/prodotti/rose-rosse";
  private static final String DEMO_READY = "https://" + DEMO + "/q/health/ready";
  private static final String DEMO_INTERNAL = "https://" + DEMO + "/internal/tls/allowed";

  record Result(int exitCode, String stdout, String stderr) {}

  record Smoke(Result result, String log) {}

  // --- Caddyfile, adapted by the pinned Caddy image -------------------------------------------

  @Test
  void caddyAdaptsTheCaddyfileWithExactlyTheBaseAndDemoHosts() throws Exception {
    List<Map<String, Object>> routes = topLevelRoutes();
    Set<String> allHosts = new TreeSet<>();
    for (Map<String, Object> route : routes) {
      List<String> hosts = hostsOf(route);
      assertFalse(hosts.isEmpty(), "a top-level route has no host matcher (catch-all): " + route);
      allHosts.addAll(hosts);
    }
    assertEquals(new TreeSet<>(Set.of(BASE, DEMO)), allHosts, "hosts served by Caddy");
  }

  @Test
  void eachHostHasHstsHiddenManagementPathsAndTheProxy() throws Exception {
    List<Map<String, Object>> routes = topLevelRoutes();
    for (String host : List.of(BASE, DEMO)) {
      List<Map<String, Object>> forHost =
          routes.stream().filter(r -> hostsOf(r).contains(host)).toList();
      assertFalse(forHost.isEmpty(), "no route with a host matcher containing " + host);
      boolean ok =
          forHost.stream()
              .anyMatch(
                  r ->
                      hasHsts(r)
                          && answers404For(r, "/internal/*")
                          && answers404For(r, "/q/*")
                          && proxiesToApp(r));
      assertTrue(
          ok,
          "no route for "
              + host
              + " with Strict-Transport-Security, 404 on /internal/* and /q/*, and"
              + " reverse_proxy to app:8080: "
              + forHost);
    }
  }

  // --- smoke.sh against a fake curl -----------------------------------------------------------

  @Test
  void smokePassesAndAsksForTheDemoCatalogAndProduct() throws Exception {
    Smoke s = smoke("NONE");
    assertEquals(0, s.result().exitCode(), "smoke.sh exit; stderr: " + s.result().stderr());
    assertFalse(s.log().contains("UNEXPECTED"), "smoke.sh asked an unexpected URL:\n" + s.log());
    assertTrue(s.log().contains(CATALOG), "smoke.sh did not ask " + CATALOG + ":\n" + s.log());
    assertTrue(s.log().contains(PRODUCT), "smoke.sh did not ask " + PRODUCT + ":\n" + s.log());
  }

  @Test
  void smokeFailsWhenTheDemoCatalogLacksTheShopName() throws Exception {
    assertSmokeFails("CATALOG_TEXT", CATALOG);
  }

  @Test
  void smokeFailsWhenTheDemoProductLacksTheProductName() throws Exception {
    assertSmokeFails("PRODUCT_TEXT", PRODUCT);
  }

  @Test
  void smokeFailsWhenTheDemoReadinessIsPublic() throws Exception {
    assertSmokeFails("DEMO_HEALTH_PUBLIC", DEMO_READY);
  }

  @Test
  void smokeFailsWhenTheDemoInternalPathIsPublic() throws Exception {
    assertSmokeFails("DEMO_INTERNAL_PUBLIC", DEMO_INTERNAL);
  }

  @Test
  void smokeFailsWhenTheDemoHasNoHsts() throws Exception {
    assertSmokeFails("DEMO_NO_HSTS", "head https://" + DEMO + "/");
  }

  @Test
  void smokeFailsWhenTheDemoHttpDoesNotRedirectWith308() throws Exception {
    assertSmokeFails("DEMO_NO_308", "http://" + DEMO + "/");
  }

  private static void assertSmokeFails(String defect, String expectedInLog) throws Exception {
    Smoke s = smoke(defect);
    assertEquals(
        1,
        s.result().exitCode(),
        "smoke.sh must fail with defect " + defect + "; log:\n" + s.log());
    assertTrue(
        s.log().contains(expectedInLog),
        "smoke.sh did not ask " + expectedInLog + " (defect " + defect + "):\n" + s.log());
  }

  // --- fake curl ------------------------------------------------------------------------------

  /**
   * A bash stand-in for curl. It logs one line per call, "&lt;mode&gt; &lt;url&gt;" with mode w
   * (has -w), head (has -I) or body, and answers like the lab site. Combined short options are
   * split (-sI = -s -I); -w wins over -I and prints only the write-out with %{http_code} replaced;
   * -f with a code &gt;= 400 exits 22; -o writes the content to the file. A URL outside the known
   * set is logged as UNEXPECTED and the fake exits 6. DEFECT turns on one fault on the demo host.
   */
  private static final String FAKE_CURL =
      """
      #!/usr/bin/env bash
      DEFECT='@DEFECT@'
      LOG='@LOG@'
      BASE='@BASE@'
      DEMO="demo.$BASE"
      head=0; fail=0; has_w=0; wfmt=''; out=''; url=''
      args=("$@")
      i=0
      while [ "$i" -lt "${#args[@]}" ]; do
        a="${args[$i]}"
        case "$a" in
          --head) head=1 ;;
          --fail|--fail-with-body) fail=1 ;;
          --write-out) i=$((i+1)); wfmt="${args[$i]}"; has_w=1 ;;
          --output) i=$((i+1)); out="${args[$i]}" ;;
          --max-time|--connect-timeout|--header|--request|--user-agent|--retry|--retry-delay|--retry-max-time|--dump-header|--resolve) i=$((i+1)) ;;
          --*) ;;
          -?*)
            rest="${a#-}"
            while [ -n "$rest" ]; do
              c="${rest:0:1}"; rest="${rest:1}"
              case "$c" in
                I) head=1 ;;
                f) fail=1 ;;
                w|o|m|H|X|A|D|e|u)
                  if [ -n "$rest" ]; then v="$rest"; rest=''; else i=$((i+1)); v="${args[$i]}"; fi
                  case "$c" in w) wfmt="$v"; has_w=1 ;; o) out="$v" ;; esac ;;
                *) ;;
              esac
            done ;;
          *) url="$a" ;;
        esac
        i=$((i+1))
      done
      if [ "$has_w" = 1 ]; then mode=w; elif [ "$head" = 1 ]; then mode=head; else mode=body; fi
      unexpected() {
        printf 'UNEXPECTED %s %s\\n' "$mode" "$url" >> "$LOG"
        echo "fake curl: unexpected URL '$url'" >&2
        exit 6
      }
      printf '%s %s\\n' "$mode" "$url" >> "$LOG"
      case "$url" in http://*|https://*) ;; *) unexpected ;; esac
      scheme="${url%%://*}"; rest="${url#*://}"; host="${rest%%/*}"
      if [ "$rest" = "$host" ]; then path=/; else path="/${rest#*/}"; fi
      path="${path%%\\?*}"
      [ "$host" = "$BASE" ] || [ "$host" = "$DEMO" ] || unexpected
      code=''; body=''; location=''; hsts=1
      if [ "$host" = "$DEMO" ] && [ "$DEFECT" = DEMO_NO_HSTS ]; then hsts=0; fi
      if [ "$scheme" = http ]; then
        if [ "$host" = "$DEMO" ] && [ "$DEFECT" = DEMO_NO_308 ]; then
          code=200; body='<h1>Fioreria Il Glicine</h1>'
        else
          code=308; location="https://$host$path"
        fi
      else
        case "$path" in
          /q/health/ready)
            if [ "$host" = "$DEMO" ] && [ "$DEFECT" = DEMO_HEALTH_PUBLIC ]; then
              code=200; body='{"status":"UP"}'
            else code=404; fi ;;
          /internal/tls/allowed)
            if [ "$host" = "$DEMO" ] && [ "$DEFECT" = DEMO_INTERNAL_PUBLIC ]; then
              code=200; body='ok'
            else code=404; fi ;;
          /q/*|/internal/*) code=404 ;;
          /)
            code=200
            if [ "$host" = "$BASE" ]; then body='<h1>Negozi locali online</h1>'
            else body='<h1>Fioreria Il Glicine</h1><a href="/catalogo">Catalogo</a>'; fi ;;
          /catalogo)
            [ "$host" = "$DEMO" ] || unexpected
            code=200
            if [ "$DEFECT" = CATALOG_TEXT ]; then body='<h1>Catalogo</h1><p>Mazzo di rose rosse</p>'
            else body='<h1>Fioreria Il Glicine</h1><p>Mazzo di rose rosse</p>'; fi ;;
          /prodotti/rose-rosse)
            [ "$host" = "$DEMO" ] || unexpected
            code=200
            if [ "$DEFECT" = PRODUCT_TEXT ]; then body='<h1>Prodotto</h1><p>12 rose</p>'
            else body='<h1>Mazzo di rose rosse</h1><p>12 rose</p>'; fi ;;
          /webhooks/stripe/connect)
            # Giro 012: the Stripe Connect webhook answers on the base host only.
            if [ "$host" = "$BASE" ]; then code=400; body='bad signature'; else code=404; fi ;;
          *) unexpected ;;
        esac
      fi
      if [ "$head" = 1 ]; then
        content="HTTP/2 $code"$'\\r\\n'
        [ -n "$location" ] && content+="location: $location"$'\\r\\n'
        [ "$scheme" = https ] && [ "$hsts" = 1 ] && \\
          content+='strict-transport-security: max-age=31536000; includeSubDomains'$'\\r\\n'
        content+='content-type: text/html;charset=UTF-8'$'\\r\\n'$'\\r\\n'
      else
        content="$body"
      fi
      pat='%{http_code}'
      if [ "$fail" = 1 ] && [ "$code" -ge 400 ]; then
        [ "$has_w" = 1 ] && printf '%s' "${wfmt//"$pat"/$code}"
        echo "curl: (22) The requested URL returned error: $code" >&2
        exit 22
      fi
      if [ -n "$out" ] && [ "$out" != /dev/null ] && [ "$out" != - ]; then
        printf '%s' "$content" > "$out"
      elif [ "$has_w" = 0 ] && [ "$out" != /dev/null ]; then
        printf '%s' "$content"
      fi
      [ "$has_w" = 1 ] && printf '%s' "${wfmt//"$pat"/$code}"
      exit 0
      """;

  private static Smoke smoke(String defect) throws Exception {
    Path script = INFRA.resolve("smoke.sh");
    assertTrue(Files.isExecutable(script), "not executable: " + script);
    Path dir = Files.createTempDirectory("giro006-curl");
    Path log = dir.resolve("calls.log");
    Files.createFile(log);
    Path curl = dir.resolve("curl");
    Files.writeString(
        curl,
        FAKE_CURL
            .replace("@DEFECT@", defect)
            .replace("@LOG@", log.toAbsolutePath().toString())
            .replace("@BASE@", BASE),
        StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(curl, PosixFilePermissions.fromString("rwx------"));
    String path =
        dir.toAbsolutePath() + ":" + System.getenv().getOrDefault("PATH", "/usr/bin:/bin");
    Result r =
        run(
            Duration.ofSeconds(60),
            null,
            Map.of(
                "PATH", path, "LAB_BASE_DOMAIN", BASE, "SMOKE_ATTEMPTS", "1", "SMOKE_DELAY", "0"),
            script.toString());
    String calls = Files.readString(log, StandardCharsets.UTF_8);
    Files.delete(curl);
    Files.delete(log);
    Files.delete(dir);
    return new Smoke(r, calls);
  }

  // --- caddy adapt and JSON walking -----------------------------------------------------------

  private static List<Map<String, Object>> topLevelRoutes() throws Exception {
    Path caddyfile = INFRA.resolve("Caddyfile");
    assertTrue(Files.isRegularFile(caddyfile), "missing " + caddyfile);
    Result r =
        run(
            Duration.ofSeconds(240),
            caddyfile,
            Map.of(),
            "docker",
            "run",
            "--rm",
            "-i",
            "-e",
            "LAB_BASE_DOMAIN=" + BASE,
            CADDY_IMAGE,
            "caddy",
            "adapt",
            "--config",
            "/dev/stdin",
            "--adapter",
            "caddyfile");
    assertEquals(0, r.exitCode(), "caddy adapt: " + r.stderr());
    Map<String, Object> servers = JsonPath.from(r.stdout()).getMap("apps.http.servers");
    assertTrue(servers != null && !servers.isEmpty(), "no http servers: " + r.stdout());
    List<Map<String, Object>> routes = new ArrayList<>();
    for (Object server : servers.values()) {
      Object list = ((Map<?, ?>) server).get("routes");
      if (list instanceof List<?> l) {
        for (Object route : l) {
          routes.add(asMap(route));
        }
      }
    }
    assertFalse(routes.isEmpty(), "no routes: " + r.stdout());
    return routes;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return (Map<String, Object>) o;
  }

  private static List<String> hostsOf(Map<String, Object> route) {
    List<String> hosts = new ArrayList<>();
    if (route.get("match") instanceof List<?> matchers) {
      for (Object m : matchers) {
        if (m instanceof Map<?, ?> map && map.get("host") instanceof List<?> h) {
          h.forEach(x -> hosts.add(String.valueOf(x)));
        }
      }
    }
    return hosts;
  }

  /** All maps in the subtree of a node, the node included. */
  private static List<Map<?, ?>> maps(Object node) {
    List<Map<?, ?>> found = new ArrayList<>();
    collect(node, found);
    return found;
  }

  private static void collect(Object node, List<Map<?, ?>> found) {
    if (node instanceof Map<?, ?> map) {
      found.add(map);
      map.values().forEach(v -> collect(v, found));
    } else if (node instanceof Collection<?> c) {
      c.forEach(v -> collect(v, found));
    }
  }

  private static boolean hasHsts(Map<String, Object> route) {
    return maps(route).stream()
        .anyMatch(
            m ->
                m.keySet().stream()
                    .anyMatch(
                        k -> "strict-transport-security".equalsIgnoreCase(String.valueOf(k))));
  }

  private static boolean answers404For(Map<String, Object> route, String pathPattern) {
    for (Map<?, ?> m : maps(route)) {
      if (!(m.get("match") instanceof List<?> matchers)
          || !(m.get("handle") instanceof List<?> h)) {
        continue;
      }
      boolean pathMatches =
          matchers.stream()
              .anyMatch(
                  x ->
                      x instanceof Map<?, ?> mm
                          && mm.get("path") instanceof List<?> paths
                          && paths.contains(pathPattern));
      boolean is404 =
          h.stream()
              .anyMatch(
                  x ->
                      x instanceof Map<?, ?> hm
                          && "static_response".equals(hm.get("handler"))
                          && "404".equals(String.valueOf(hm.get("status_code"))));
      if (pathMatches && is404) {
        return true;
      }
    }
    return false;
  }

  private static boolean proxiesToApp(Map<String, Object> route) {
    return maps(route).stream()
        .anyMatch(
            m ->
                "reverse_proxy".equals(m.get("handler"))
                    && m.get("upstreams") instanceof List<?> ups
                    && ups.stream()
                        .anyMatch(
                            u -> u instanceof Map<?, ?> um && "app:8080".equals(um.get("dial"))));
  }

  // --- process helper -------------------------------------------------------------------------

  private static Result run(
      Duration timeout, Path stdin, Map<String, String> env, String... command) throws Exception {
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
    Path out = Files.createTempFile("giro006-out", ".txt");
    Path err = Files.createTempFile("giro006-err", ".txt");
    pb.redirectOutput(out.toFile());
    pb.redirectError(err.toFile());
    pb.redirectInput(
        ProcessBuilder.Redirect.from((stdin != null ? stdin : Path.of("/dev/null")).toFile()));
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
