package it.gerio.platform.notifications;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Runs {@link OutboxWorker#drainOnce()} every 30 seconds with a Vert.x timer (no scheduler
 * extension). The pass runs on a worker thread, never on the event loop.
 */
@ApplicationScoped
public class OutboxTimer {

  private static final Logger LOG = Logger.getLogger(OutboxTimer.class);
  private static final long PERIOD_MS = 30_000;

  @Inject Vertx vertx;
  @Inject OutboxWorker worker;

  @ConfigProperty(name = "app.outbox.worker-enabled")
  boolean enabled;

  void onStart(@Observes StartupEvent event) {
    if (enabled) {
      vertx.setPeriodic(PERIOD_MS, id -> vertx.executeBlocking(this::pass, false));
    }
  }

  private Void pass() {
    try {
      worker.drainOnce();
    } catch (RuntimeException e) {
      LOG.errorf("Outbox pass failed (%s)", e.getClass().getSimpleName());
    }
    return null;
  }
}
