package it.gerio.platform.notifications;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * The {@code outbox_message} table (SPEC 6, 9): a global table, written and read with native SQL,
 * which Hibernate does not filter by shop. {@code tenant_id} is filled by hand. The payload holds
 * ids only, never personal data.
 */
@ApplicationScoped
public class Outbox {

  /** After this many failed attempts a row is FAILED and nobody tries again. */
  static final int MAX_ATTEMPTS = 8;

  private static final int BATCH = 20;
  private static final Duration LEASE = Duration.ofMinutes(5);

  /** A row taken for delivery. */
  record Row(long id, String tenantId, long orderId) {}

  @Inject EntityManager em;
  @Inject DataSource dataSource;
  @Inject Clock clock;

  /**
   * Queues the order confirmation in the caller's transaction (the one that marks the order paid).
   * One per order: a second insert is ignored.
   */
  public void enqueueConfirmation(String tenantId, long orderId) {
    Instant now = clock.instant();
    em.createNativeQuery(
            "insert into outbox_message (tenant_id, channel, payload, status, attempts,"
                + " next_attempt_at, created_at) values (:tenant, 'EMAIL',"
                + " cast(:payload as jsonb), 'PENDING', 0, :now, :now) on conflict do nothing")
        .setParameter("tenant", tenantId)
        .setParameter("payload", "{\"kind\":\"ORDER_CONFIRMATION\",\"orderId\":" + orderId + "}")
        .setParameter("now", now)
        .executeUpdate();
  }

  /**
   * Takes the due rows and moves their {@code next_attempt_at} 5 minutes ahead (a lease), so that
   * another worker, or this one after a crash, does not take them at once. {@code skip locked}
   * keeps two workers from taking the same row.
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  List<Row> take(Instant now) {
    List<Row> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement select =
          connection.prepareStatement(
              "select id, tenant_id, (payload->>'orderId')::bigint from outbox_message"
                  + " where status = 'PENDING' and channel = 'EMAIL' and next_attempt_at <= ?"
                  + " order by next_attempt_at, id limit "
                  + BATCH
                  + " for update skip locked")) {
        select.setTimestamp(1, Timestamp.from(now));
        try (ResultSet found = select.executeQuery()) {
          while (found.next()) {
            rows.add(new Row(found.getLong(1), found.getString(2), found.getLong(3)));
          }
        }
      }
      for (Row row : rows) {
        update(
            connection,
            "update outbox_message set next_attempt_at = ? where id = ?",
            Timestamp.from(now.plus(LEASE)),
            row.id());
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Outbox: cannot take rows", e);
    }
    return rows;
  }

  @Transactional(Transactional.TxType.REQUIRES_NEW)
  void markSent(long id) {
    try (Connection connection = dataSource.getConnection()) {
      update(
          connection,
          "update outbox_message set status = 'SENT', last_error = null where id = ?",
          id);
    } catch (SQLException e) {
      throw new IllegalStateException("Outbox: cannot mark row " + id + " sent", e);
    }
  }

  /**
   * One more failed attempt: wait 1, 2, 4... minutes from the clock; the last one marks the row
   * FAILED.
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  void markFailed(long id, String error) {
    Instant now = clock.instant();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("select attempts from outbox_message where id = ?")) {
      select.setLong(1, id);
      int attempts;
      try (ResultSet found = select.executeQuery()) {
        found.next();
        attempts = found.getInt(1) + 1;
      }
      boolean failed = attempts >= MAX_ATTEMPTS;
      try (PreparedStatement update =
          connection.prepareStatement(
              "update outbox_message set attempts = ?, last_error = ?, status = ?,"
                  + " next_attempt_at = ? where id = ?")) {
        update.setInt(1, attempts);
        update.setString(2, error);
        update.setString(3, failed ? "FAILED" : "PENDING");
        update.setTimestamp(
            4, Timestamp.from(now.plus(Duration.ofMinutes(1L << Math.min(attempts - 1, 20)))));
        update.setLong(5, id);
        update.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Outbox: cannot record the failure of row " + id, e);
    }
  }

  private static void update(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }
}
