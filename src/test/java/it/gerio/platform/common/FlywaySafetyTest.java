package it.gerio.platform.common;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

// Tests the behaviour, not the property: if clean ever got re-enabled, this test
// would wipe the throwaway Dev Services database and fail loudly -- far better
// than finding out in production.
@QuarkusTest
class FlywaySafetyTest {

  @Inject Flyway flyway;

  @Test
  void refusesToCleanTheDatabase() {
    assertThrows(FlywayException.class, flyway::clean);
  }
}
