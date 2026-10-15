package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gerio.platform.acceptance.Giro013Shell.Result;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Giro 013 (goal T5, last part): infra/prova-stripe.sh takes an order of the demo shop up to the
 * Stripe Checkout link ("start"), then reads the outcome page ("esito"). Black box: a fake curl
 * plays the demo shop (cart kept in a state file, cookie jar and CSRF checked like the app), a fake
 * sleep records the waits. Never contacts a server; the script runs by hand, never in CI.
 */
class Giro013ProvaStripeAcceptanceTest {

  private static final Path SCRIPT = Path.of("infra", "prova-stripe.sh");
  private static final String DEMO = "demo.lab.example.test";
  private static final String CSRF = "Z2lybzAxMy1jc3JmLXRva2Vu";
  private static final String MIN_DATE = "2031-05-17";
  private static final String SESSION = "cs_test_a1Giro013Glicine";
  private static final String FRAGMENT = "#fidkdWxOYHwnPyd1blpxYHZxWjA0";
  private static final String CHECKOUT_URL =
      "https://checkout.stripe.com/c/pay/" + SESSION + FRAGMENT;
  private static final String OUTCOME_URL =
      "https://" + DEMO + "/ordine/esito?session_id=" + SESSION;
  private static final String ORDER_NUMBER = "2026-0042";

  record Call(String method, String url, boolean follow, String jarIn, String jarOut, String data) {
    Map<String, String> form() {
      Map<String, String> fields = new LinkedHashMap<>();
      if (data.isEmpty()) {
        return fields;
      }
      for (String kv : data.split("&")) {
        int eq = kv.indexOf('=');
        String k = eq < 0 ? kv : kv.substring(0, eq);
        String v = eq < 0 ? "" : kv.substring(eq + 1);
        fields.put(
            URLDecoder.decode(k, StandardCharsets.UTF_8),
            URLDecoder.decode(v, StandardCharsets.UTF_8));
      }
      return fields;
    }

    boolean is(String m, String path) {
      return method.equals(m) && url.startsWith("https://" + DEMO + path);
    }
  }

  record Run(Result result, List<Call> calls, List<String> sleeps, String log, Path tmp) {
    List<Call> calls(String method, String path) {
      return calls.stream().filter(c -> c.is(method, path)).toList();
    }

    String why() {
      return "exit "
          + result.exitCode()
          + "\nstdout: "
          + result.stdout()
          + "\nstderr: "
          + result.stderr()
          + "\nlog:\n"
          + log;
    }
  }

  // --- start: from the cart to the Checkout link ----------------------------------------------

  @Test
  void startPrintsOnlyTheCheckoutUrlAndExits0() throws Exception {
    Run r = run("NONE", DEMO);
    assertEquals(0, r.result().exitCode(), r.why());
    assertEquals(CHECKOUT_URL, r.result().stdout().strip(), "stdout must be only the URL");
    assertFalse(r.log().contains("UNEXPECTED"), r.why());
  }

  @Test
  void startAddsTheVariantsOfTheRightRowsWithTheirQuantities() throws Exception {
    Run r = run("NONE", DEMO);
    Map<String, Integer> quantities = new HashMap<>();
    for (Call c : r.calls("POST", "/carrello/aggiungi")) {
      Map<String, String> f = c.form();
      int q = Integer.parseInt(f.getOrDefault("quantity", "1"));
      quantities.merge(f.get("variantId"), q, Integer::sum);
    }
    assertEquals(
        Map.of("101", 1, "201", 2),
        quantities,
        "variantId of «12 rose» × 1 and of «Vaso 12 cm» × 2 (the first row is another variant)\n"
            + r.why());
  }

  @Test
  void everyPostCarriesTheCsrfValueServedByThePages() throws Exception {
    Run r = run("NONE", DEMO);
    List<Call> posts = r.calls().stream().filter(c -> c.method().equals("POST")).toList();
    assertFalse(posts.isEmpty(), r.why());
    for (Call c : posts) {
      assertEquals(CSRF, c.form().get("csrf-token"), "csrf-token of " + c.url() + "\n" + r.why());
    }
  }

  @Test
  void theCheckoutPostHasGiuliaTheMinPickupDateAndDoesNotFollowTheRedirect() throws Exception {
    Run r = run("NONE", DEMO);
    List<Call> checkout = r.calls("POST", "/checkout");
    assertEquals(1, checkout.size(), "one POST /checkout\n" + r.why());
    Call c = checkout.get(0);
    Map<String, String> f = c.form();
    assertEquals("Giulia Bianchi", f.get("customerName"), r.why());
    assertEquals("giulia.bianchi@example.com", f.get("customerEmail"), r.why());
    assertEquals("+39 333 000 1234", f.get("customerPhone"), r.why());
    assertEquals(MIN_DATE, f.get("pickupDate"), "pickupDate = min of the summary\n" + r.why());
    assertFalse(c.follow(), "POST /checkout must not use -L\n" + r.why());
  }

  @Test
  void everyCallUsesOneMktempCookieJarThatIsGoneAfterwardsAndChangesAtEveryRun() throws Exception {
    Run first = run("NONE", DEMO);
    String jar = assertOneJar(first);
    Run second = run("NONE", DEMO);
    String other = assertOneJar(second);
    assertNotEquals(jar, other, "the cookie jar must change at every run");
  }

  @Test
  void checkoutOffExits1WithoutPostingTheOrder() throws Exception {
    Run r = run("CHECKOUT_OFF", DEMO);
    assertEquals(1, r.result().exitCode(), r.why());
    assertTrue(r.result().stderr().contains("checkout spento"), r.why());
    assertTrue(r.calls("POST", "/checkout").isEmpty(), "no POST /checkout\n" + r.why());
    assertFalse(r.result().stdout().contains("https://"), r.why());
    assertOneJar(r);
  }

  @Test
  void aDifferentTotalExits1WithoutPostingTheOrder() throws Exception {
    Run r = run("PRICE_CHANGED", DEMO);
    assertEquals(1, r.result().exitCode(), "the summary shows 95,00 €, not 104,80 €\n" + r.why());
    assertTrue(r.calls("POST", "/checkout").isEmpty(), "no POST /checkout\n" + r.why());
  }

  @Test
  void aRedirectToAHostThatOnlyLooksLikeStripeExits1() throws Exception {
    Run r = run("STRIPE_LOOKALIKE", DEMO);
    assertEquals(1, r.result().exitCode(), r.why());
    assertFalse(r.result().stdout().contains("https://"), "no URL in stdout\n" + r.why());
  }

  @Test
  void withoutArgumentsExits2AndCallsNothing() throws Exception {
    Run r = run("NONE");
    assertEquals(2, r.result().exitCode(), r.why());
    assertTrue(r.calls().isEmpty(), r.why());
  }

  @Test
  void theScriptIsExecutableAndBashNIsClean() throws Exception {
    assertTrue(Files.isExecutable(SCRIPT), "not executable: " + SCRIPT);
    Result r = Giro013Shell.run(Duration.ofSeconds(30), Map.of(), "bash", "-n", SCRIPT.toString());
    assertEquals(0, r.exitCode(), "bash -n: " + r.stderr());
  }

  // --- esito: the outcome page ----------------------------------------------------------------

  @Test
  void esitoWithTheCheckoutUrlAsksForTheSessionWithoutFragmentAndPrintsTheOrder() throws Exception {
    Run r = run("ESITO_PAID", "esito", DEMO, CHECKOUT_URL);
    assertEquals(0, r.result().exitCode(), r.why());
    assertTrue(r.result().stdout().contains(ORDER_NUMBER), "order number\n" + r.why());
    List<Call> gets = r.calls("GET", "/ordine/esito");
    assertEquals(1, gets.size(), r.why());
    assertEquals(OUTCOME_URL, gets.get(0).url(), r.why());
    assertNoOrderCreated(r);
  }

  @Test
  void esitoRetriesUntilThePaymentIsConfirmed() throws Exception {
    Run r = run("ESITO_LATE", "esito", DEMO, CHECKOUT_URL);
    assertEquals(0, r.result().exitCode(), r.why());
    assertEquals(
        3, r.calls("GET", "/ordine/esito").size(), "paid at the third request\n" + r.why());
    assertTrue(r.result().stdout().contains(ORDER_NUMBER), r.why());
    assertSleepsOf2Seconds(r);
  }

  @Test
  void esitoStillPendingAfter15RequestsEvery2SecondsExits1() throws Exception {
    Run r = run("ESITO_PENDING", "esito", DEMO, CHECKOUT_URL);
    assertEquals(1, r.result().exitCode(), r.why());
    assertTrue(r.result().stderr().contains("in attesa"), r.why());
    assertEquals(15, r.calls("GET", "/ordine/esito").size(), r.why());
    assertSleepsOf2Seconds(r);
    assertTrue(
        r.sleeps().size() == 14 || r.sleeps().size() == 15, "14 or 15 waits of 2 s: " + r.sleeps());
    assertNoOrderCreated(r);
  }

  @Test
  void esitoOfAnExpiredSessionExits1AtOnce() throws Exception {
    Run r = run("ESITO_EXPIRED", "esito", DEMO, CHECKOUT_URL);
    assertEquals(1, r.result().exitCode(), r.why());
    assertTrue(r.result().stderr().contains("scaduta"), r.why());
    assertEquals(1, r.calls("GET", "/ordine/esito").size(), "no retry when expired\n" + r.why());
  }

  @Test
  void esitoOfAnUnknownSessionNeverExits0() throws Exception {
    Run r =
        run(
            "ESITO_PAID",
            "esito",
            DEMO,
            "https://checkout.stripe.com/c/pay/cs_test_b2AltraSessione" + FRAGMENT);
    assertEquals(1, r.result().exitCode(), "404 from the outcome page\n" + r.why());
    assertTrue(r.result().stderr().contains("sconosciuta"), r.why());
  }

  // --- checks -----------------------------------------------------------------------------------

  private static String assertOneJar(Run r) {
    assertFalse(r.calls().isEmpty(), r.why());
    String jar = r.calls().get(0).jarIn();
    assertFalse(jar.isEmpty(), "no -b cookie jar\n" + r.why());
    for (Call c : r.calls()) {
      assertEquals(jar, c.jarIn(), "-b of " + c.url() + "\n" + r.why());
      assertEquals(jar, c.jarOut(), "-c of " + c.url() + "\n" + r.why());
    }
    assertTrue(
        Path.of(jar).toAbsolutePath().startsWith(r.tmp().toAbsolutePath()),
        "the jar must come from mktemp under TMPDIR: " + jar);
    assertFalse(Files.exists(Path.of(jar)), "the cookie jar is still there: " + jar);
    return jar;
  }

  private static void assertSleepsOf2Seconds(Run r) {
    assertFalse(r.sleeps().isEmpty(), "no wait between the requests\n" + r.why());
    for (String s : r.sleeps()) {
      assertEquals("2", s, "wait between the requests\n" + r.why());
    }
  }

  private static void assertNoOrderCreated(Run r) {
    assertTrue(
        r.calls().stream().noneMatch(c -> c.method().equals("POST")), "esito posts\n" + r.why());
  }

  // --- fake curl and sleep ----------------------------------------------------------------------

  /**
   * The demo shop as the script sees it. One line per call in the log: CALL, method, URL (the
   * #fragment dropped, as curl does), follow=0|1 (-L), jin= (-b), jout= (-c), data= (all -d and
   * --data-urlencode joined by &amp;). Cookies are read from the -b file and written to the -c
   * file. The cart lives in a state file and starts empty when the request has no cart cookie.
   * POSTs need the csrf-token cookie and the same value in the form, else 400.
   */
  private static final String FAKE_CURL =
      """
      #!/usr/bin/env bash
      export LC_ALL=C
      DEFECT='@DEFECT@'
      LOG='@LOG@'
      STATE='@STATE@'
      DEMO='@DEMO@'
      CSRF='@CSRF@'
      SESSION='@SESSION@'
      MIN='@MIN@'
      FRAG='@FRAG@'
      TAB=$'\\t'; NL=$'\\n'; CR=$'\\r'
      enc() {
        local s="$1" k='' v out='' c j
        case "$s" in *=*) k="${s%%=*}="; v="${s#*=}" ;; *) v="$s" ;; esac
        for ((j=0; j<${#v}; j++)); do
          c="${v:j:1}"
          case "$c" in [a-zA-Z0-9.~_-]) out+="$c" ;; *) out+=$(printf '%%%02X' "'$c") ;; esac
        done
        printf '%s%s' "$k" "$out"
      }
      data=''
      add_data() { if [ -n "$data" ]; then data+="&$1"; else data="$1"; fi; }
      method=''; head=0; fail=0; follow=0; include=0; has_w=0; wfmt=''; out=''; dump=''
      jin=''; jout=''; url=''
      args=("$@"); i=0
      while [ "$i" -lt "${#args[@]}" ]; do
        a="${args[$i]}"
        case "$a" in
          --head) head=1 ;;
          --fail|--fail-with-body) fail=1 ;;
          --location|--location-trusted) follow=1 ;;
          --include) include=1 ;;
          --write-out) i=$((i+1)); wfmt="${args[$i]}"; has_w=1 ;;
          --output) i=$((i+1)); out="${args[$i]}" ;;
          --dump-header) i=$((i+1)); dump="${args[$i]}" ;;
          --cookie) i=$((i+1)); jin="${args[$i]}" ;;
          --cookie-jar) i=$((i+1)); jout="${args[$i]}" ;;
          --request) i=$((i+1)); method="${args[$i]}" ;;
          --data|--data-raw|--data-ascii|--data-binary) i=$((i+1)); add_data "${args[$i]}" ;;
          --data-urlencode) i=$((i+1)); add_data "$(enc "${args[$i]}")" ;;
          --max-time|--connect-timeout|--header|--user-agent|--retry|--retry-delay|--retry-max-time|--resolve|--proto|--proto-redir|--max-redirs|--referer) i=$((i+1)) ;;
          --*) ;;
          -?*)
            rest="${a#-}"
            while [ -n "$rest" ]; do
              c="${rest:0:1}"; rest="${rest:1}"
              case "$c" in
                I) head=1 ;;
                f) fail=1 ;;
                L) follow=1 ;;
                i) include=1 ;;
                G) method=GET ;;
                w|o|D|b|c|X|d|m|H|A|e|u)
                  if [ -n "$rest" ]; then v="$rest"; rest=''; else i=$((i+1)); v="${args[$i]}"; fi
                  case "$c" in
                    w) wfmt="$v"; has_w=1 ;;
                    o) out="$v" ;;
                    D) dump="$v" ;;
                    b) jin="$v" ;;
                    c) jout="$v" ;;
                    X) method="$v" ;;
                    d) add_data "$v" ;;
                  esac ;;
                *) ;;
              esac
            done ;;
          *) url="$a" ;;
        esac
        i=$((i+1))
      done
      url="${url%%#*}"
      if [ "$method" = GET ] && [ -n "$data" ]; then
        case "$url" in *[?]*) url="$url&$data" ;; *) url="$url?$data" ;; esac
        data=''
      fi
      if [ -z "$method" ]; then
        if [ -n "$data" ]; then method=POST; elif [ "$head" = 1 ]; then method=HEAD; else method=GET; fi
      fi
      printf 'CALL\\t%s\\t%s\\tfollow=%s\\tjin=%s\\tjout=%s\\tdata=%s\\n' \\
        "$method" "$url" "$follow" "$jin" "$jout" "$data" >> "$LOG"
      unexpected() {
        printf 'UNEXPECTED\\t%s\\t%s\\n' "$method" "$url" >> "$LOG"
        echo "fake curl: unexpected $method $url" >&2
        exit 6
      }
      case "$url" in https://*) ;; *) unexpected ;; esac
      rest="${url#https://}"; host="${rest%%/*}"
      if [ "$rest" = "$host" ]; then path=/; else path="/${rest#*/}"; fi
      query=''
      case "$path" in *[?]*) query="${path#*[?]}"; path="${path%%[?]*}" ;; esac
      [ "$host" = "$DEMO" ] || unexpected

      declare -A ck
      if [ -n "$jin" ] && [ -f "$jin" ]; then
        while IFS="$TAB" read -r d f p s e n v; do
          case "$d" in ''|'#'*) continue ;; esac
          [ -n "$n" ] && ck["$n"]="$v"
        done < "$jin"
      fi
      setc=''
      set_cookie() { ck["$1"]="$2"; setc+="set-cookie: $1=$2; Path=/; HttpOnly; SameSite=Lax$CR$NL"; }
      field() {
        local IFS='&' kv
        for kv in $data; do
          case "$kv" in "$1="*) printf '%s' "${kv#*=}"; return 0 ;; esac
        done
        return 1
      }
      csrf_ok() { [ "${ck[csrf-token]:-}" = "$CSRF" ] && [ "$(field csrf-token)" = "$CSRF" ]; }
      price() {
        case "$1" in
          101) echo 4500 ;;
          102) echo 8500 ;;
          201) if [ "$DEFECT" = PRICE_CHANGED ]; then echo 2500; else echo 2990; fi ;;
          202) echo 1990 ;;
          *) echo '' ;;
        esac
      }
      euro() { printf '%d,%02d €' $(($1/100)) $(($1%100)); }
      total() {
        local t=0 vid q
        if [ -f "$STATE/cart" ]; then
          while read -r vid q; do t=$((t + $(price "$vid") * q)); done < "$STATE/cart"
        fi
        echo "$t"
      }
      variant() {
        printf '      <li><span>%s</span> <span>%s</span>\\n' "$2" "$3"
        printf '        <form method="post" action="/carrello/aggiungi">\\n'
        printf '          <input type="hidden" name="csrf-token" value="%s">\\n' "$CSRF"
        printf '          <input type="hidden" name="variantId" value="%s">\\n' "$1"
        printf '          <input type="hidden" name="quantity" value="1">\\n'
        printf '          <button type="submit">Aggiungi al carrello</button>\\n'
        printf '        </form>\\n      </li>\\n'
      }
      checkout_form() {
        printf '    <form method="post" action="/checkout">\\n'
        printf '      <input type="hidden" name="csrf-token" value="%s">\\n' "$CSRF"
        printf '      <p><label for="customerName">Nome e cognome</label>\\n'
        printf '        <input id="customerName" type="text" name="customerName" value="" required maxlength="200"></p>\\n'
        printf '      <p><label for="customerEmail">Email</label>\\n'
        printf '        <input id="customerEmail" type="email" name="customerEmail" value="" required maxlength="200"></p>\\n'
        printf '      <p><label for="customerPhone">Telefono (facoltativo)</label>\\n'
        printf '        <input id="customerPhone" type="tel" name="customerPhone" value="" maxlength="40"></p>\\n'
        printf '      <p><label for="pickupDate">Giorno di ritiro</label>\\n'
        printf '        <input id="pickupDate" type="date" name="pickupDate" value="" min="%s" required></p>\\n' "$MIN"
        printf '      <p>Procedendo accetti le <a href="/condizioni-di-vendita">condizioni di vendita</a>.</p>\\n'
        printf '      <button type="submit">Ordina e paga</button>\\n    </form>\\n'
      }
      code=200; body=''; location=''
      case "$method $path" in
        "GET /prodotti/rose-rosse")
          set_cookie csrf-token "$CSRF"
          body="<h1>Mazzo di rose rosse</h1>$NL    <ul>$NL$(variant 102 '24 rose' '85,00 €')$NL$(variant 101 '12 rose' '45,00 €')$NL    </ul>$NL    <p>IVA inclusa</p>" ;;
        "GET /prodotti/orchidea-bianca")
          set_cookie csrf-token "$CSRF"
          body="<h1>Orchidea Phalaenopsis bianca</h1>$NL    <ul>$NL$(variant 202 'Vaso 9 cm' '19,90 €')$NL$(variant 201 'Vaso 12 cm' "$(euro "$(price 201)")")$NL    </ul>$NL    <p>IVA inclusa</p>" ;;
        "GET /carrello")
          set_cookie csrf-token "$CSRF"
          body="<h1>Carrello</h1>$NL    <p>Totale: <strong>$(euro "$(total)")</strong></p>" ;;
        "POST /carrello/aggiungi")
          if ! csrf_ok; then
            code=400; body='Richiesta non valida'
          else
            vid="$(field variantId)"; q="$(field quantity)"; [ -z "$q" ] && q=1
            if [ -z "$(price "$vid")" ]; then
              code=404; body='<h1>Pagina non trovata</h1>'
            elif ! [[ "$q" =~ ^[1-9][0-9]?$ ]]; then
              code=400; body='Richiesta non valida'
            else
              if [ -z "${ck[cart]:-}" ]; then : > "$STATE/cart"; set_cookie cart giro013-cart; fi
              echo "$vid $q" >> "$STATE/cart"
              code=303; location=/carrello
            fi
          fi ;;
        "GET /checkout")
          set_cookie csrf-token "$CSRF"
          t=0; [ -n "${ck[cart]:-}" ] && t="$(total)"
          if [ "$t" = 0 ]; then
            code=303; location=/carrello
          else
            body="<h1>Riepilogo ordine</h1>$NL    <p>Totale: <strong>$(euro "$t")</strong></p>$NL    <p>IVA inclusa. Ritiro in negozio, nessun costo di consegna.</p>$NL"
            if [ "$DEFECT" = CHECKOUT_OFF ]; then
              body+="    <p>Al momento non è possibile pagare online. Contatta il negozio per ordinare.</p>"
            else
              body+="$(checkout_form)"
            fi
          fi ;;
        "POST /checkout")
          if ! csrf_ok; then
            code=400; body='Richiesta non valida'
          elif [ -z "${ck[cart]:-}" ]; then
            code=303; location=/carrello
          elif [ "$(field pickupDate)" != "$MIN" ] || [ -z "$(field customerName)" ] || [ -z "$(field customerEmail)" ]; then
            body="<h1>Riepilogo ordine</h1>$NL    <p role=\\"alert\\">Controlla i dati inseriti.</p>$NL$(checkout_form)"
          else
            code=303
            if [ "$DEFECT" = STRIPE_LOOKALIKE ]; then
              location="https://checkout.stripe.com.evil.example.test/c/pay/$SESSION$FRAG"
            else
              location="https://checkout.stripe.com/c/pay/$SESSION$FRAG"
            fi
          fi ;;
        "GET /ordine/esito")
          sid=''
          IFS='&' read -ra qs <<< "$query"
          for kv in "${qs[@]}"; do case "$kv" in session_id=*) sid="${kv#session_id=}" ;; esac; done
          n=0; [ -f "$STATE/esito" ] && n="$(cat "$STATE/esito")"; n=$((n+1)); echo "$n" > "$STATE/esito"
          paid="<h1>Pagamento ricevuto</h1>$NL    <p>Ordine 2026-0042</p>$NL    <p>Totale: <strong>104,80 €</strong></p>"
          pending="<meta http-equiv=\\"refresh\\" content=\\"2;url=/ordine/esito?session_id=$SESSION&amp;n=1\\">$NL    <h1>Stiamo confermando il pagamento…</h1>$NL    <p>Ordine 2026-0042</p>"
          expired="<h1>Il pagamento non è stato completato</h1>$NL    <p>Ordine 2026-0042</p>$NL    <p><a href=\\"/carrello\\">Torna al carrello</a></p>"
          if [ "$sid" != "$SESSION" ]; then
            code=404; body='<h1>Pagina non trovata</h1>'
          else
            case "$DEFECT" in
              ESITO_PAID) body="$paid" ;;
              ESITO_LATE) if [ "$n" -ge 3 ]; then body="$paid"; else body="$pending"; fi ;;
              ESITO_EXPIRED) body="$expired" ;;
              *) body="$pending" ;;
            esac
          fi ;;
        *) unexpected ;;
      esac
      body="<!doctype html>$NL<html lang=\\"it\\"><head><title>Fioreria Il Glicine</title></head>$NL<body>$NL    $body$NL</body></html>$NL"
      loc_abs="$location"
      case "$location" in /*) loc_abs="https://$DEMO$location" ;; esac
      hdr="HTTP/2 $code$CR$NL"
      [ -n "$location" ] && hdr+="location: $location$CR$NL"
      hdr+="$setc"
      hdr+="content-type: text/html;charset=UTF-8$CR$NL$CR$NL"
      if [ -n "$jout" ]; then
        {
          printf '# Netscape HTTP Cookie File\\n'
          for n in "${!ck[@]}"; do printf '%s\\tFALSE\\t/\\tTRUE\\t0\\t%s\\t%s\\n' "$DEMO" "$n" "${ck[$n]}"; done
        } > "$jout"
      fi
      wout() {
        local w="$wfmt" redir=''
        if [ "$code" -ge 300 ] && [ "$code" -lt 400 ]; then redir="$loc_abs"; fi
        w="${w//'%{http_code}'/$code}"
        w="${w//'%{response_code}'/$code}"
        w="${w//'%{redirect_url}'/$redir}"
        w="${w//'%header{location}'/$location}"
        w="${w//'%{url_effective}'/$url}"
        w="${w//'\\n'/$NL}"
        printf '%s' "$w"
      }
      if [ -n "$dump" ]; then
        if [ "$dump" = - ]; then printf '%s' "$hdr"; else printf '%s' "$hdr" > "$dump"; fi
      fi
      if [ "$head" = 1 ]; then content="$hdr"; elif [ "$include" = 1 ]; then content="$hdr$body"; else content="$body"; fi
      if [ "$fail" = 1 ] && [ "$code" -ge 400 ]; then
        [ "$has_w" = 1 ] && wout
        echo "curl: (22) The requested URL returned error: $code" >&2
        exit 22
      fi
      if [ -n "$out" ] && [ "$out" != - ]; then
        [ "$out" != /dev/null ] && printf '%s' "$content" > "$out"
      else
        printf '%s' "$content"
      fi
      [ "$has_w" = 1 ] && wout
      exit 0
      """;

  private static final String FAKE_SLEEP =
      """
      #!/usr/bin/env bash
      printf 'SLEEP\\t%s\\n' "$*" >> '@LOG@'
      exit 0
      """;

  private static Run run(String defect, String... args) throws Exception {
    assertTrue(Files.isRegularFile(SCRIPT), "missing " + SCRIPT);
    Path dir = Files.createTempDirectory("giro013-prova");
    Path bin = Files.createDirectory(dir.resolve("bin"));
    Path state = Files.createDirectory(dir.resolve("state"));
    Path tmp = Files.createDirectory(dir.resolve("tmp"));
    Path log = dir.resolve("calls.log");
    Files.createFile(log);
    String logPath = log.toAbsolutePath().toString();
    Giro013Shell.install(
        bin,
        "curl",
        FAKE_CURL
            .replace("@DEFECT@", defect)
            .replace("@LOG@", logPath)
            .replace("@STATE@", state.toAbsolutePath().toString())
            .replace("@DEMO@", DEMO)
            .replace("@CSRF@", CSRF)
            .replace("@SESSION@", SESSION)
            .replace("@MIN@", MIN_DATE)
            .replace("@FRAG@", FRAGMENT));
    Giro013Shell.install(bin, "sleep", FAKE_SLEEP.replace("@LOG@", logPath));
    List<String> command = new ArrayList<>();
    command.add("bash");
    command.add(SCRIPT.toString());
    command.addAll(List.of(args));
    Result result =
        Giro013Shell.run(
            Duration.ofSeconds(120),
            Map.of("PATH", Giro013Shell.pathWith(bin), "TMPDIR", tmp.toAbsolutePath().toString()),
            command.toArray(String[]::new));
    String text = Files.readString(log, StandardCharsets.UTF_8);
    List<Call> calls = new ArrayList<>();
    List<String> sleeps = new ArrayList<>();
    for (String line : text.split("\n")) {
      String[] p = line.split("\t", -1);
      if (p[0].equals("CALL") && p.length >= 7) {
        calls.add(
            new Call(
                p[1],
                p[2],
                p[3].equals("follow=1"),
                p[4].substring("jin=".length()),
                p[5].substring("jout=".length()),
                p[6].substring("data=".length())));
      } else if (p[0].equals("SLEEP")) {
        sleeps.add(p.length > 1 ? p[1].strip() : "");
      }
    }
    Run run = new Run(result, calls, sleeps, text, tmp);
    // The jar check reads the paths first; clean up only the fakes, the log and the state.
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .filter(p -> !p.startsWith(tmp))
          .filter(p -> !p.equals(dir))
          .forEach(p -> p.toFile().delete());
    }
    return run;
  }
}
