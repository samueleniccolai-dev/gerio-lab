package it.gerio.platform.payment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gerio.platform.order.CustomerOrder;
import it.gerio.platform.order.OrderStateMachine;
import it.gerio.platform.order.OrderStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** A real payment on an order that already expired is a WARN: money came for a lost order. */
class StripeEventProcessorTest {

  private final StripeEventProcessor processor = new StripeEventProcessor();
  private final List<LogRecord> records = new ArrayList<>();

  StripeEventProcessorTest() {
    processor.stateMachine = new OrderStateMachine();
  }

  private Level logCompleted(OrderStatus status) {
    CustomerOrder order = new CustomerOrder();
    order.status = status;
    Logger logger = Logger.getLogger(StripeEventProcessor.class.getName());
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      processor.completed(
          "evt_1",
          order,
          new StripeEventProcessor.SessionData("cs_1", "complete", "paid", "pi_1", "1", "t"));
    } finally {
      logger.removeHandler(handler);
    }
    return records.isEmpty() ? null : records.get(records.size() - 1).getLevel();
  }

  @Test
  void paymentOnExpiredOrderLogsWarn() {
    Level level = logCompleted(OrderStatus.EXPIRED);

    assertTrue(level != null && level.intValue() >= Level.WARNING.intValue(), "level: " + level);
  }
}
