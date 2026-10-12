package it.gerio.platform.payment;

import static io.restassured.RestAssured.given;
import static it.gerio.platform.tenant.TestShops.SHOP_A;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import it.gerio.platform.acceptance.Giro011FakeStripe;
import it.gerio.platform.tenant.TenantStatus;
import it.gerio.platform.tenant.TestShops;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deadlock guard (giro 011, "Rischi"): a checkout locks the cart and then the order; the webhook of
 * the same order must not wait for the cart. A transaction holds the cart row, as the checkout
 * does, while the webhook arrives. The webhook must finish by itself with 200, and the checkout
 * side must then get the order lock.
 */
@QuarkusTest
@WithTestResource(Giro011FakeStripe.class)
class WebhookCartLockTest {

  private static final String SECRET = "whsec_giro011_finto";
  private static final String ACCOUNT = "acct_1LockTest";

  @Inject DataSource dataSource;

  private TestShops shops;
  private long cartId;
  private long orderId;

  @BeforeEach
  void shopWithACartAndAPendingOrder() throws SQLException {
    shops = new TestShops(dataSource);
    shops.insertTenant(SHOP_A, "fioraia-a", "Fioraia A", TenantStatus.ACTIVE);
    cartId = insert("insert into cart (tenant_id, token) values (?, 'tok-lock') returning id");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "update tenant set stripe_account_id = '" + ACCOUNT + "' where id = '" + SHOP_A + "'");
    }
    orderId =
        insert(
            "insert into customer_order (tenant_id, number, public_token, status,"
                + " fulfillment_type, customer_name, customer_email, delivery_date,"
                + " subtotal_cents, delivery_fee_cents, total_cents, vat_breakdown,"
                + " terms_version, cart_id, stripe_checkout_session_id) values (?, '2026-0001',"
                + " 'tok', 'PENDING_PAYMENT', 'PICKUP', 'Cliente', 'cliente@example.test',"
                + " date '2026-10-13', 1000, 0, 1000, '[]', 'test', "
                + cartId
                + ", 'cs_test_lock') returning id");
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("delete from stripe_event");
      statement.execute("delete from order_event");
      statement.execute("delete from customer_order");
      statement.execute("delete from cart");
    }
    shops.deleteAll();
  }

  @Test
  void theWebhookDoesNotWaitForTheCartLockedByACheckout() throws Exception {
    String payload = paidEvent();
    try (Connection checkout = dataSource.getConnection()) {
      checkout.setAutoCommit(false);
      try (Statement statement = checkout.createStatement()) {
        // What the checkout does first: lock the cart.
        statement.execute("select id from cart where id = " + cartId + " for update");

        Response response =
            CompletableFuture.supplyAsync(() -> post(payload)).get(30, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode(), "webhook while the cart is locked");

        // What the checkout does next: lock the order. It must not be blocked by the webhook.
        statement.execute("set local lock_timeout = '5s'");
        statement.execute("select id from customer_order where id = " + orderId + " for update");
      } finally {
        checkout.rollback();
      }
    }
    assertEquals("PAID", status());
  }

  private String paidEvent() {
    return "{\"id\":\"evt_lock_1\",\"object\":\"event\",\"api_version\":\"2026-09-30.endive\""
        + ",\"created\":"
        + Instant.now().getEpochSecond()
        + ",\"livemode\":false,\"type\":\"checkout.session.completed\",\"account\":\""
        + ACCOUNT
        + "\",\"data\":{\"object\":{\"id\":\"cs_test_lock\",\"object\":\"checkout.session\""
        + ",\"status\":\"complete\",\"payment_status\":\"paid\",\"payment_intent\":\"pi_lock\""
        + ",\"client_reference_id\":\""
        + orderId
        + "\",\"metadata\":{\"tenant_id\":\""
        + SHOP_A
        + "\"}}}}";
  }

  private static Response post(String payload) {
    long t = Instant.now().getEpochSecond();
    String signature;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8));
      signature = "t=" + t + ",v1=" + HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
    return given()
        .header("Host", "localhost")
        .header("Stripe-Signature", signature)
        .contentType("application/json")
        .body(payload.getBytes(StandardCharsets.UTF_8))
        .when()
        .post("/webhooks/stripe/connect");
  }

  private String status() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("select status from customer_order where id = " + orderId)) {
      rows.next();
      return rows.getString(1);
    }
  }

  private long insert(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setString(1, SHOP_A);
      try (ResultSet rows = insert.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }
}
