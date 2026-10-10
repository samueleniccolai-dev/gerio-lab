package it.gerio.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

// Separate from ClockProducerTest: this one boots CDI to prove the @Produces
// method is actually wired in as an injectable bean, not just that calling it
// directly returns a UTC clock.
@QuarkusTest
class ClockInjectionTest {

  @Inject Clock clock;

  @Test
  void injectsAUtcClock() {
    assertEquals(ZoneOffset.UTC, clock.getZone());
  }
}
