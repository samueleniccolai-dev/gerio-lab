package it.gerio.platform.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OutboxWorkerTest {

  @Test
  void errorTextKeepsClassAndMessageButNotEmailAddresses() {
    String text =
        OutboxWorker.describe(
            new IllegalStateException("550 <giulia.bianchi@example.com> user unknown"));
    assertTrue(text.startsWith("java.lang.IllegalStateException: 550"), text);
    assertFalse(text.contains("giulia"), text);
    assertFalse(text.contains("@"), text);
  }

  @Test
  void errorTextIsShortEnoughForTheColumn() {
    assertEquals(500, OutboxWorker.describe(new RuntimeException("x".repeat(2000))).length());
  }
}
