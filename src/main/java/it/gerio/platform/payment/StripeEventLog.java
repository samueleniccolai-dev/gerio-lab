package it.gerio.platform.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Clock;

/**
 * The {@code stripe_event} table (SPEC 6, 8.4): a global table written with native SQL, which
 * Hibernate does not filter by shop. It is what makes a repeated event harmless.
 */
@ApplicationScoped
public class StripeEventLog {

  @Inject EntityManager em;
  @Inject Clock clock;

  /**
   * Records the event if it is new, in its own transaction, and says whether it was already
   * processed. Two deliveries at the same time both see "not processed": the order lock decides.
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public boolean registerAndCheckProcessed(String id, String type, String account) {
    em.createNativeQuery(
            "insert into stripe_event (id, type, account, received_at)"
                + " values (:id, :type, :account, :now) on conflict (id) do nothing")
        .setParameter("id", id)
        .setParameter("type", type)
        .setParameter("account", account)
        .setParameter("now", clock.instant())
        .executeUpdate();
    Object processed =
        em.createNativeQuery("select processed_at from stripe_event where id = :id")
            .setParameter("id", id)
            .getSingleResultOrNull();
    return processed != null;
  }

  /** Marks the event processed, in the caller's transaction. */
  public void markProcessed(String id) {
    em.createNativeQuery("update stripe_event set processed_at = :now where id = :id")
        .setParameter("id", id)
        .setParameter("now", clock.instant())
        .executeUpdate();
  }

  /** Marks the event processed in a transaction of its own (events nobody can process). */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void markProcessedAlone(String id) {
    markProcessed(id);
  }
}
