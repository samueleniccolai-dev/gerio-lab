package it.gerio.platform.payment;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Creates Stripe Checkout sessions on a shop's connected account (SPEC 8.3). The only class that
 * knows the Stripe library. It never logs the answer of Stripe, the form or the key: only the
 * client reference and the error code.
 */
@ApplicationScoped
public class StripeCheckout {

  private static final Logger LOG = Logger.getLogger(StripeCheckout.class);
  private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
  private static final int READ_TIMEOUT_MILLIS = 20_000;
  // Retries 409 and 5xx with the same idempotency key, so a retry never creates a second session.
  private static final int MAX_NETWORK_RETRIES = 2;

  /** One line of the session. */
  public record Line(String name, long unitCents, long quantity) {}

  /** Everything Stripe is told about a session. Same request, same key, same parameters. */
  public record Request(
      String accountId,
      String idempotencyKey,
      String clientReference,
      String tenantId,
      String orderNumber,
      String customerEmail,
      Instant expiresAt,
      String successUrl,
      String cancelUrl,
      List<Line> lines) {}

  /** The session to send the customer to. */
  public record Session(String id, String url) {}

  /** Stripe did not give a session (error, timeout, no answer). */
  public static class PaymentUnavailableException extends Exception {
    public PaymentUnavailableException(String message) {
      super(message);
    }
  }

  @Inject StripeConfig config;

  private StripeClient client;

  @PostConstruct
  void init() {
    client =
        config
            .secretKey()
            .filter(key -> !key.isBlank())
            .filter(StripeCheckout::notLive)
            .map(
                key -> {
                  var builder =
                      StripeClient.builder()
                          .setApiKey(key)
                          .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                          .setReadTimeout(READ_TIMEOUT_MILLIS)
                          .setMaxNetworkRetries(MAX_NETWORK_RETRIES);
                  config.apiBase().filter(base -> !base.isBlank()).ifPresent(builder::setApiBase);
                  return builder.build();
                })
            .orElse(null);
  }

  private static boolean notLive(String key) {
    if (key.startsWith("sk_live_") || key.startsWith("rk_live_")) {
      LOG.warn("Stripe live key refused: only test keys are accepted, checkout is off");
      return false;
    }
    return true;
  }

  /** False without a secret key: the checkout is off. */
  public boolean configured() {
    return client != null;
  }

  public Session create(Request request) throws PaymentUnavailableException {
    if (client == null) {
      throw new PaymentUnavailableException("Stripe is not configured");
    }
    SessionCreateParams.Builder params =
        SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setCurrency("eur")
            .setClientReferenceId(request.clientReference())
            .putMetadata("tenant_id", request.tenantId())
            .putMetadata("order_number", request.orderNumber())
            .setCustomerEmail(request.customerEmail())
            .setExpiresAt(request.expiresAt().getEpochSecond())
            .setSuccessUrl(request.successUrl())
            .setCancelUrl(request.cancelUrl());
    for (Line line : request.lines()) {
      params.addLineItem(
          SessionCreateParams.LineItem.builder()
              .setQuantity(line.quantity())
              .setPriceData(
                  SessionCreateParams.LineItem.PriceData.builder()
                      .setCurrency("eur")
                      .setUnitAmount(line.unitCents())
                      .setProductData(
                          SessionCreateParams.LineItem.PriceData.ProductData.builder()
                              .setName(line.name())
                              .build())
                      .build())
              .build());
    }
    RequestOptions options =
        RequestOptions.builder()
            .setStripeAccount(request.accountId())
            .setIdempotencyKey(request.idempotencyKey())
            .build();
    try {
      com.stripe.model.checkout.Session session =
          client.v1().checkout().sessions().create(params.build(), options);
      if (session.getId() == null || session.getUrl() == null) {
        throw new PaymentUnavailableException("Stripe answered without a session");
      }
      return new Session(session.getId(), session.getUrl());
    } catch (StripeException e) {
      LOG.warnf(
          "Stripe refused the session of order %s: status %s, code %s",
          request.clientReference(), e.getStatusCode(), e.getCode());
      throw new PaymentUnavailableException("Stripe error");
    } catch (RuntimeException e) {
      LOG.warnf(
          "Stripe call failed for order %s: %s", request.clientReference(), e.getClass().getName());
      throw new PaymentUnavailableException("Stripe unreachable");
    }
  }
}
