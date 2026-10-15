package it.gerio.platform.order;

/** Order states (SPEC 7.1). Only the ones the code can reach so far. */
public enum OrderStatus {
  PENDING_PAYMENT("In attesa di pagamento"),
  PAID("Pagato"),
  EXPIRED("Pagamento non completato");

  private final String label;

  OrderStatus(String label) {
    this.label = label;
  }

  /** The state as the customer reads it. */
  public String label() {
    return label;
  }
}
