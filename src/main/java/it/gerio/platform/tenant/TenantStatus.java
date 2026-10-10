package it.gerio.platform.tenant;

/** Lifecycle of a shop; mirrors the {@code tenant_status_known} check constraint in V1. */
public enum TenantStatus {
  /** Just created: not public until the platform admin activates it. */
  DRAFT,
  /** Public. */
  ACTIVE,
  /** Temporarily offline (e.g. unpaid subscription). */
  SUSPENDED
}
