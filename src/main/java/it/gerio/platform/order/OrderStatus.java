package it.gerio.platform.order;

/** Order states (SPEC 7.1). Only the ones the code can reach so far. */
public enum OrderStatus {
  PENDING_PAYMENT,
  PAID,
  EXPIRED
}
