package it.gerio.platform.payment;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import it.gerio.platform.payment.StripeEventProcessor.SessionData;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.ResolvedShop;
import it.gerio.platform.tenant.TenantContext;
import it.gerio.platform.tenant.TenantDirectory;
import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The Stripe Connect webhook (SPEC 8.4). Only on the platform domain. Steps: signature on the exact
 * bytes of the body, record in {@code stripe_event} (a repeated event ends here), find the shop by
 * the account of the event, work for that shop in a new transaction. Any failure after the record
 * is a 500 and leaves the event unprocessed, so Stripe sends it again. The body is never logged.
 */
@Path("/webhooks/stripe/connect")
public class StripeWebhookResource {

  static final String COMPLETED = "checkout.session.completed";
  static final String EXPIRED = "checkout.session.expired";

  private static final Logger LOG = Logger.getLogger(StripeWebhookResource.class);

  @Inject TenantContext tenantContext;
  @Inject TenantDirectory directory;
  @Inject StripeConfig config;
  @Inject StripeEventLog eventLog;
  @Inject StripeEventProcessor processor;

  @POST
  public Response receive(@HeaderParam("Stripe-Signature") String signature, String payload)
      throws Exception {
    if (!(tenantContext.require() instanceof CurrentTenant.Platform)) {
      return Response.status(404).build();
    }
    Optional<String> secret = config.connectWebhookSecret().filter(s -> !s.isBlank());
    if (secret.isEmpty()) {
      LOG.warn("Stripe webhook called but no signing secret is configured");
      return Response.status(503).build();
    }
    if (signature == null || payload == null) {
      return Response.status(400).build();
    }
    Event event;
    try {
      // Exception to "Clock everywhere": the 300 s tolerance is on the system time on purpose,
      // because Stripe signs with real time (the app Clock may be moved, e.g. in tests).
      event = Webhook.constructEvent(payload, signature, secret.get());
    } catch (SignatureVerificationException e) {
      LOG.warn("Stripe webhook: signature refused");
      return Response.status(400).build();
    }

    boolean done =
        eventLog.registerAndCheckProcessed(event.getId(), event.getType(), event.getAccount());
    if (done) {
      return Response.ok().build();
    }
    LOG.infof("Stripe event %s (%s)", event.getId(), event.getType());

    // Read with deserializeUnsafe(), not getObject(): getObject() is empty when the event's
    // api_version is not the library's. A field of the wrong type throws: 500, event unprocessed.
    boolean handled = COMPLETED.equals(event.getType()) || EXPIRED.equals(event.getType());
    SessionData session =
        handled ? readSession(event) : new SessionData(null, null, null, null, null, null);
    Optional<ResolvedShop> shop = directory.findByStripeAccount(event.getAccount());
    if (shop.isEmpty()) {
      LOG.infof("Stripe event %s: unknown account", event.getId());
      eventLog.markProcessedAlone(event.getId());
      return Response.ok().build();
    }
    tenantContext.switchTo(shop.get().toCurrentTenant());
    processor.process(event.getId(), event.getType(), session);
    return Response.ok().build();
  }

  private static SessionData readSession(Event event) throws Exception {
    Session session = (Session) event.getDataObjectDeserializer().deserializeUnsafe();
    Map<String, String> metadata = session.getMetadata();
    return new SessionData(
        session.getId(),
        session.getStatus(),
        session.getPaymentStatus(),
        session.getPaymentIntent(),
        session.getClientReferenceId(),
        metadata == null ? null : metadata.get("tenant_id"));
  }
}
