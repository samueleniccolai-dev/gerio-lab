package it.gerio.platform.common;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test moves by hand. Install it with {@code QuarkusMock.installMockForType(clock,
 * Clock.class)}: every bean that injects {@link Clock} then sees this time, for that test only.
 */
public final class TestClock extends Clock {

  private volatile Instant now;

  public TestClock(Instant start) {
    this.now = start;
  }

  public void set(Instant instant) {
    this.now = instant;
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("Always UTC");
  }
}
