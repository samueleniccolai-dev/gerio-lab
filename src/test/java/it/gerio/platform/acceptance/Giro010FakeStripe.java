package it.gerio.platform.acceptance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Giro 010 test resource: a fake Stripe API on localhost (JDK HttpServer, multi-thread executor).
 * It answers only {@code POST /v1/checkout/sessions} and keeps every request it receives, with
 * headers and decoded form. It behaves like Stripe on idempotency keys: same key and same
 * parameters replay the stored answer (a 500 too); same key while the first request is still
 * running gives 409 {@code idempotency_key_in_use}; same key with other parameters gives 400.
 *
 * <p>The app is pointed here with {@code app.stripe.api-base} and gets a fake test key in {@code
 * app.stripe.secret-key}. Tests that use it run with {@code @WithTestResource(Giro010FakeStripe)}.
 */
public class Giro010FakeStripe implements QuarkusTestResourceLifecycleManager {

  static final String SECRET_KEY = "sk_test_giro010_fake";
  static final String SESSIONS_PATH = "/v1/checkout/sessions";
  static final String URL_PREFIX = "https://checkout.stripe.com/c/pay/";

  private static volatile Giro010FakeStripe current;

  /** One request received by the fake. */
  record Call(String method, String path, Map<String, String> headers, Map<String, String> form) {
    String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    String idempotencyKey() {
      return header("Idempotency-Key");
    }
  }

  private record Stored(Map<String, String> form, int status, String body) {}

  private HttpServer server;
  private ExecutorService executor;
  private final List<Call> calls = new CopyOnWriteArrayList<>();
  private final Map<String, Object> keys = new ConcurrentHashMap<>();
  private final List<String> sessions = new CopyOnWriteArrayList<>();
  private final AtomicInteger counter = new AtomicInteger();
  private volatile long delayMillis;
  private final AtomicInteger failNewKeys = new AtomicInteger();

  static Giro010FakeStripe get() {
    if (current == null) {
      throw new IllegalStateException("the fake Stripe is not running");
    }
    return current;
  }

  @Override
  public Map<String, String> start() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("cannot start the fake Stripe", e);
    }
    executor = Executors.newFixedThreadPool(16);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
    current = this;
    Map<String, String> config = new LinkedHashMap<>();
    config.put("app.stripe.api-base", "http://127.0.0.1:" + server.getAddress().getPort());
    config.put("app.stripe.secret-key", SECRET_KEY);
    return config;
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
    current = null;
  }

  // --- controls used by the tests -------------------------------------------------------------

  /** Forgets every call, key and session; no delay, no failures. */
  void reset() {
    calls.clear();
    keys.clear();
    sessions.clear();
    delayMillis = 0;
    failNewKeys.set(0);
  }

  /** Each new session request waits this long before answering. */
  void delay(long millis) {
    delayMillis = millis;
  }

  /** The next {@code n} requests with a new idempotency key get a 500, stored for that key. */
  void failNewKeys(int n) {
    failNewKeys.set(n);
  }

  List<Call> sessionCalls() {
    List<Call> found = new ArrayList<>();
    for (Call call : calls) {
      if ("POST".equals(call.method()) && SESSIONS_PATH.equals(call.path())) {
        found.add(call);
      }
    }
    return found;
  }

  List<Call> allCalls() {
    return List.copyOf(calls);
  }

  /** Ids of the sessions actually created (replays are not counted). */
  List<String> createdSessions() {
    return List.copyOf(sessions);
  }

  static String urlOf(String sessionId) {
    return URL_PREFIX + sessionId;
  }

  // --- HTTP -----------------------------------------------------------------------------------

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      Map<String, String> headers = new LinkedHashMap<>();
      exchange
          .getRequestHeaders()
          .forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
      Map<String, String> form = decode(body);
      String path = exchange.getRequestURI().getPath();
      Call call = new Call(exchange.getRequestMethod(), path, headers, form);
      calls.add(call);

      if (!"POST".equals(call.method()) || !SESSIONS_PATH.equals(path)) {
        send(exchange, 404, error("invalid_request_error", null, "Unrecognized request URL"));
        return;
      }
      String key = call.idempotencyKey();
      if (key == null) {
        Stored result = create(form);
        send(exchange, result.status(), result.body());
        return;
      }
      Object marker = new Object();
      Object previous = keys.putIfAbsent(key, marker);
      if (previous != null) {
        if (!(previous instanceof Stored stored)) {
          send(
              exchange,
              409,
              error(
                  "idempotency_error",
                  "idempotency_key_in_use",
                  "There is currently another in-progress request using this Idempotent Key."));
          return;
        }
        if (!stored.form().equals(form)) {
          send(
              exchange,
              400,
              error(
                  "idempotency_error",
                  null,
                  "Keys for idempotent requests can only be used with the same parameters"
                      + " they were first used with."));
          return;
        }
        exchange.getResponseHeaders().add("Idempotent-Replayed", "true");
        send(exchange, stored.status(), stored.body());
        return;
      }
      Stored result = create(form);
      keys.put(key, result);
      send(exchange, result.status(), result.body());
    }
  }

  private Stored create(Map<String, String> form) {
    long delay = delayMillis;
    if (delay > 0) {
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (failNewKeys.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
      return new Stored(form, 500, error("api_error", null, "Fake Stripe internal error"));
    }
    String id = "cs_test_giro010_" + counter.incrementAndGet();
    sessions.add(id);
    String json =
        "{\"id\":\""
            + id
            + "\",\"object\":\"checkout.session\",\"livemode\":false,\"mode\":\"payment\","
            + "\"status\":\"open\",\"payment_status\":\"unpaid\",\"currency\":\"eur\","
            + "\"url\":\""
            + urlOf(id)
            + "\"}";
    return new Stored(form, 200, json);
  }

  private static String error(String type, String code, String message) {
    return "{\"error\":{\"type\":\""
        + type
        + "\""
        + (code == null ? "" : ",\"code\":\"" + code + "\"")
        + ",\"message\":\""
        + message
        + "\"}}";
  }

  private static void send(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.getResponseHeaders().add("Request-Id", "req_giro010_" + System.nanoTime());
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static Map<String, String> decode(String body) {
    Map<String, String> form = new LinkedHashMap<>();
    if (body.isEmpty()) {
      return form;
    }
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      String name = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      form.put(
          URLDecoder.decode(name, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return form;
  }
}
