package it.gerio.platform.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * Injects the current time everywhere it's needed, instead of calling {@code Instant.now()}
 * directly. Tests can then swap in a fixed {@link Clock} to simulate any moment (order cutoffs,
 * delivery slots) and get repeatable results. PROGETTO.md §4.3.
 *
 * <p>Always UTC: instants are stored as {@code timestamptz} and converted to Europe/Rome only when
 * shown to a person (CLAUDE.md conventions).
 */
@ApplicationScoped
public class ClockProducer {

  @Produces
  @ApplicationScoped
  public Clock clock() {
    return Clock.systemUTC();
  }
}
