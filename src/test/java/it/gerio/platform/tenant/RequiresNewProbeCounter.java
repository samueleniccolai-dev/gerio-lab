package it.gerio.platform.tenant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * The §5.2 fix for a request that already used the database: the shop work runs on another bean, in
 * a new transaction, so Hibernate opens a new session and reads the tenant again.
 *
 * <p>Another bean on purpose: a call to a method of the same class skips the CDI interceptor, and
 * {@code REQUIRES_NEW} would be silently ignored.
 */
@ApplicationScoped
public class RequiresNewProbeCounter {

  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public long count() {
    return ProbeItem.count();
  }
}
