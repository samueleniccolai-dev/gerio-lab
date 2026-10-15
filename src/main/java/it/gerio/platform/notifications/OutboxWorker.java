package it.gerio.platform.notifications;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.mailer.Mailer;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.ResolvedShop;
import it.gerio.platform.tenant.TenantContext;
import it.gerio.platform.tenant.TenantDirectory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Sends the queued emails (SPEC 9.1). One pass takes the due rows (as the platform), then works on
 * each row in a request context of its own, with the tenant context set to the shop of the row
 * (never switched from one shop to another). The email goes out outside any transaction; the result
 * is written afterwards. A crash between the two can send an email twice, never lose one.
 */
@ApplicationScoped
public class OutboxWorker {

  private static final Logger LOG = Logger.getLogger(OutboxWorker.class);

  private final AtomicBoolean running = new AtomicBoolean();

  @Inject Outbox outbox;
  @Inject ConfirmationComposer composer;
  @Inject TenantDirectory directory;
  @Inject TenantContext tenantContext;
  @Inject Mailer mailer;
  @Inject Clock clock;

  @ConfigProperty(name = "app.mail.enabled")
  boolean mailEnabled;

  /** One pass; a pass that finds another one running in this process returns at once. */
  public void drainOnce() {
    if (!mailEnabled) {
      LOG.debug("Outbox: sending is off, rows wait");
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      for (Outbox.Row row : takeDue()) {
        deliver(row);
      }
    } finally {
      running.set(false);
    }
  }

  private java.util.List<Outbox.Row> takeDue() {
    return outbox.take(clock.instant());
  }

  private void deliver(Outbox.Row row) {
    ManagedContext requestContext = Arc.container().requestContext();
    var outer = requestContext.isActive() ? requestContext.getState() : null;
    if (outer != null) {
      requestContext.deactivate();
    }
    requestContext.activate();
    try {
      tenantContext.set(CurrentTenant.PLATFORM);
      ResolvedShop shop =
          directory
              .findById(row.tenantId())
              .orElseThrow(() -> new IllegalStateException("Shop of the row not found"));
      tenantContext.switchTo(shop.toCurrentTenant());
      mailer.send(composer.compose(row.orderId(), shop.primaryHost()));
      outbox.markSent(row.id());
    } catch (RuntimeException e) {
      LOG.warnf("Outbox row %d: attempt failed (%s)", row.id(), e.getClass().getSimpleName());
      outbox.markFailed(row.id(), describe(e));
    } finally {
      requestContext.terminate();
      if (outer != null) {
        requestContext.activate(outer);
      }
    }
  }

  /** Class and message of the error, without email addresses, short enough for the column. */
  static String describe(Throwable error) {
    String text = error.getClass().getName() + ": " + error.getMessage();
    text = text.replaceAll("[^\\s<>\"']+@[^\\s<>\"']+", "[address]");
    return text.length() > 500 ? text.substring(0, 500) : text;
  }
}
