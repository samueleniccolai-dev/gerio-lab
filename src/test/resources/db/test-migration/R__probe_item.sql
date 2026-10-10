-- Test-only shop table: proves tenant isolation before the first real shop table
-- exists (R1). Loaded only by the test profile (quarkus.flyway.locations).
--
-- Repeatable migration (R__): Flyway runs it after every versioned one, so it
-- never competes with the numbers of future V migrations. Dropped first so it
-- also works on a reused database.
--
-- Follows ADR-0003 point 8, like every future shop table must (SchemaRulesTest).

drop table if exists probe_item;

create table probe_item (
  id         bigint generated always as identity primary key,
  -- The foreign key also rejects rows written in the platform context: no
  -- tenant can have the id '__platform__' (V1, tenant_id_not_reserved).
  tenant_id  text not null references tenant(id),
  name       text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version    bigint not null default 0,
  -- Target of composite foreign keys from child tables (tenant_id, parent_id).
  constraint probe_item_tenant_id_id_key unique (tenant_id, id)
);

create index probe_item_tenant_id_created_at_idx on probe_item (tenant_id, created_at);
