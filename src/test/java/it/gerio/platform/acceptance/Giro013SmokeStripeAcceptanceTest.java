package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gerio.platform.acceptance.Giro013Shell.Result;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Giro 013 (goal T5, last part): when the deploy step has STRIPE_CONNECT_WEBHOOK_SECRET, smoke.sh
 * wants 400 from the Stripe Connect webhook (the signature check runs); 503 means the secret did
 * not reach the app. Without the variable it stays as in giro 012 (400 or 503). The smoke never
 * creates carts, orders or Checkout sessions. Fake curl first in PATH; never contacts a server.
 */
class Giro013SmokeStripeAcceptanceTest {

  private static final String BASE = "lab.example.test";
  private static final String SECRET = "whsec_giro013_finto";

  record Smoke(Result result, String log) {}

  @Test
  void withTheSecretAWebhookAnswering400IsGreen() throws Exception {
    Smoke s = smoke(SECRET, "400");
    assertEquals(0, s.result().exitCode(), "stderr: " + s.result().stderr() + "\nlog:\n" + s.log());
    assertNoShopFlowAndNoSecret(s);
  }

  @Test
  void withTheSecretAWebhookAnswering503IsRedAndNamesTheWebhook() throws Exception {
    assertRedForWebhook("503");
  }

  @Test
  void withTheSecretAWebhookAnswering404IsRed() throws Exception {
    assertRedForWebhook("404");
  }

  @Test
  void withTheSecretAWebhookAnswering500IsRed() throws Exception {
    assertRedForWebhook("500");
  }

  @Test
  void withoutTheSecretA503IsStillGreen() throws Exception {
    Smoke s = smoke(null, "503");
    assertEquals(0, s.result().exitCode(), "stderr: " + s.result().stderr());
    assertNoShopFlowAndNoSecret(s);
  }

  @Test
  void withoutTheSecretA400IsGreen() throws Exception {
    Smoke s = smoke(null, "400");
    assertEquals(0, s.result().exitCode(), "stderr: " + s.result().stderr());
  }

  @Test
  void anEmptySecretCountsAsAbsent() throws Exception {
    Smoke s = smoke("", "503");
    assertEquals(0, s.result().exitCode(), "stderr: " + s.result().stderr());
  }

  private static void assertRedForWebhook(String code) throws Exception {
    Smoke s = smoke(SECRET, code);
    assertEquals(
        1,
        s.result().exitCode(),
        "secret present and webhook " + code + ": smoke.sh must fail; log:\n" + s.log());
    assertTrue(
        s.result().stderr().toLowerCase().contains("webhook"),
        "the failure must name the webhook: " + s.result().stderr());
    assertNoShopFlowAndNoSecret(s);
  }

  private static void assertNoShopFlowAndNoSecret(Smoke s) {
    assertFalse(s.log().contains("UNEXPECTED"), "unexpected URL:\n" + s.log());
    for (String path : new String[] {"/checkout", "/carrello", "/ordine"}) {
      assertFalse(s.log().contains(path), "smoke.sh called " + path + ":\n" + s.log());
    }
    assertFalse(s.result().stdout().contains(SECRET), "secret in stdout");
    assertFalse(s.result().stderr().contains(SECRET), "secret in stderr");
    assertFalse(s.log().contains(SECRET), "secret in the arguments of curl");
  }

  // --- fake curl ------------------------------------------------------------------------------

  /**
   * As in giro 006: one log line per call, "&lt;mode&gt; &lt;url&gt;", plus "ARGS ..." with all the
   * arguments. The webhook on the base host answers @WEBHOOK@, on the demo host 404.
   */
  private static final String FAKE_CURL =
      """
      #!/usr/bin/env bash
      LOG='@LOG@'
      BASE='@BASE@'
      WEBHOOK='@WEBHOOK@'
      DEMO="demo.$BASE"
      printf 'ARGS %s\\n' "$*" >> "$LOG"
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
          --max-time|--connect-timeout|--header|--request|--user-agent|--retry|--retry-delay|--retry-max-time|--dump-header|--resolve|--data|--data-raw) i=$((i+1)) ;;
          --*) ;;
          -?*)
            rest="${a#-}"
            while [ -n "$rest" ]; do
              c="${rest:0:1}"; rest="${rest:1}"
              case "$c" in
                I) head=1 ;;
                f) fail=1 ;;
                w|o|m|H|X|A|D|e|u|d)
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
      code=''; body=''; location=''
      if [ "$scheme" = http ]; then
        code=308; location="https://$host$path"
      else
        case "$path" in
          /q/*|/internal/*) code=404 ;;
          /)
            code=200
            if [ "$host" = "$BASE" ]; then body='<h1>Negozi locali online</h1>'
            else body='<h1>Fioreria Il Glicine</h1><a href="/catalogo">Catalogo</a>'; fi ;;
          /catalogo)
            [ "$host" = "$DEMO" ] || unexpected
            code=200; body='<h1>Fioreria Il Glicine</h1><p>Mazzo di rose rosse</p>' ;;
          /prodotti/rose-rosse)
            [ "$host" = "$DEMO" ] || unexpected
            code=200; body='<h1>Mazzo di rose rosse</h1><p>12 rose</p>' ;;
          /webhooks/stripe/connect)
            if [ "$host" = "$BASE" ]; then code="$WEBHOOK"; body='webhook'; else code=404; fi ;;
          *) unexpected ;;
        esac
      fi
      if [ "$head" = 1 ]; then
        content="HTTP/2 $code"$'\\r\\n'
        [ -n "$location" ] && content+="location: $location"$'\\r\\n'
        [ "$scheme" = https ] && \\
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

  private static Smoke smoke(String secret, String webhookCode) throws Exception {
    Path script = Path.of("infra", "smoke.sh");
    assertTrue(Files.isExecutable(script), "not executable: " + script);
    Path dir = Files.createTempDirectory("giro013-smoke");
    Path log = dir.resolve("calls.log");
    Files.createFile(log);
    Giro013Shell.install(
        dir,
        "curl",
        FAKE_CURL
            .replace("@LOG@", log.toAbsolutePath().toString())
            .replace("@BASE@", BASE)
            .replace("@WEBHOOK@", webhookCode));
    Map<String, String> env = new HashMap<>();
    env.put("PATH", Giro013Shell.pathWith(dir));
    env.put("LAB_BASE_DOMAIN", BASE);
    env.put("SMOKE_ATTEMPTS", "1");
    env.put("SMOKE_DELAY", "0");
    if (secret != null) {
      env.put("STRIPE_CONNECT_WEBHOOK_SECRET", secret);
    }
    Result r = Giro013Shell.run(Duration.ofSeconds(60), env, script.toString());
    String calls = Files.readString(log, StandardCharsets.UTF_8);
    Files.delete(dir.resolve("curl"));
    Files.delete(log);
    Files.delete(dir);
    return new Smoke(r, calls);
  }
}
