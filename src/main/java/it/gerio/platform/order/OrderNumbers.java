package it.gerio.platform.order;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Order numbers {@code YYYY-NNNN}, per shop and year (SPEC 7.2). One atomic statement, also for the
 * first order of the year; it must run in the transaction that creates the order, so a rolled back
 * order gives its number back. Native SQL is not filtered by shop: the shop id is a parameter.
 */
@ApplicationScoped
public class OrderNumbers {

  @Inject EntityManager entityManager;

  public String next(String tenantId, int year) {
    Number value =
        (Number)
            entityManager
                .createNativeQuery(
                    "insert into tenant_counter (tenant_id, name, year, value)"
                        + " values (?1, 'order', ?2, 1)"
                        + " on conflict (tenant_id, name, year)"
                        + " do update set value = tenant_counter.value + 1"
                        + " returning value")
                .setParameter(1, tenantId)
                .setParameter(2, year)
                .getSingleResult();
    return String.format("%d-%04d", year, value.intValue());
  }
}
