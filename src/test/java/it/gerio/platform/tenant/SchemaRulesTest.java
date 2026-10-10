package it.gerio.platform.tenant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

// ADR-0003 point 8, checked on the real schema for every table that has a
// tenant_id column -- including the ones future migrations will add. Without the
// foreign key to tenant(id), a row written in the platform context would be
// accepted with tenant_id '__platform__' (TenantIsolationTest relies on it).
@QuarkusTest
class SchemaRulesTest {

  // Global tables with a plain tenant_id column, written by hand (PROGETTO.md §5.1).
  private static final Set<String> GLOBAL_TABLES =
      Set.of("tenant_domain", "outbox_message", "audit_log");

  @Inject DataSource dataSource;

  @Inject SessionFactory sessionFactory;

  @Test
  void everyShopTableFollowsTheTenantRules() throws SQLException {
    List<String> shopTables = shopTables();

    List<Executable> checks = new ArrayList<>();
    for (String table : shopTables) {
      List<String> constraints =
          query(
              "select pg_get_constraintdef(oid) from pg_constraint where conrelid = ?::regclass",
              table);
      List<String> indexes =
          query(
              "select indexdef from pg_indexes where schemaname = 'public' and tablename = ?",
              table);
      checks.add(
          () ->
              assertTrue(
                  constraints.contains("FOREIGN KEY (tenant_id) REFERENCES tenant(id)"),
                  table + " needs a foreign key to tenant(id): " + constraints));
      checks.add(
          () ->
              assertTrue(
                  constraints.contains("UNIQUE (tenant_id, id)"),
                  table + " needs unique (tenant_id, id): " + constraints));
      checks.add(
          () ->
              assertFalse(
                  indexes.stream().filter(startsWithTenantId()).findAny().isEmpty(),
                  table + " needs an index starting with tenant_id: " + indexes));
    }
    assertAll(checks);
  }

  // The schema can be perfect and the entity still unfiltered: a class that maps
  // tenant_id as a plain column, without @TenantId, lets shop A load B's rows by
  // id. Every entity on a shop table must extend TenantScopedEntity.
  // Uses Hibernate's internal metamodel (SessionFactoryImplementor,
  // AbstractEntityPersister): a Hibernate upgrade may break the compilation of
  // this test. If so, rewrite it against the new API; never disable it.
  @Test
  void everyEntityOnAShopTableExtendsTenantScopedEntity() throws SQLException {
    Set<String> shopTables = new HashSet<>(shopTables());
    Map<String, Class<?>> entitiesOnShopTables = new TreeMap<>();
    sessionFactory
        .unwrap(SessionFactoryImplementor.class)
        .getMappingMetamodel()
        .forEachEntityDescriptor(
            entity -> {
              String table = ((AbstractEntityPersister) entity).getTableName();
              if (shopTables.contains(table)) {
                entitiesOnShopTables.put(entity.getEntityName(), entity.getMappedClass());
              }
            });
    // Guard against a vacuous pass: the test-only ProbeItem is always there.
    assertTrue(
        entitiesOnShopTables.containsValue(ProbeItem.class),
        "entities found: " + entitiesOnShopTables.keySet());

    assertAll(
        entitiesOnShopTables.entrySet().stream()
            .map(
                entry ->
                    () ->
                        assertTrue(
                            TenantScopedEntity.class.isAssignableFrom(entry.getValue()),
                            entry.getKey()
                                + " maps a shop table but does not extend"
                                + " TenantScopedEntity: it would not be filtered by shop")));
  }

  // Tables with a tenant_id column, minus the global ones. Never empty: the
  // test-only probe_item is always there, so no check can pass vacuously.
  private List<String> shopTables() throws SQLException {
    List<String> tables =
        query(
            "select table_name from information_schema.columns"
                + " where table_schema = 'public' and column_name = 'tenant_id'"
                + " order by table_name",
            null);
    tables.removeAll(GLOBAL_TABLES);
    assertTrue(tables.contains("probe_item"), "tables found: " + tables);
    return tables;
  }

  private static Predicate<String> startsWithTenantId() {
    return indexDefinition -> indexDefinition.matches(".* USING \\w+ \\(tenant_id[,)].*");
  }

  // "?::regclass" turns a table name into the table's internal id (oid).
  private List<String> query(String sql, String table) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      if (table != null) {
        statement.setString(1, table);
      }
      List<String> values = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          values.add(rows.getString(1));
        }
      }
      return values;
    }
  }
}
