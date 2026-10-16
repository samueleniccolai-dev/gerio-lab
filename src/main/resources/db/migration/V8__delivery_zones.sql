-- Delivery zones (giro 017, goal T7). Additions only. SPEC 6, ADR-0003 point 8: tenant_id with a
-- foreign key to tenant(id), unique (tenant_id, id), an index starting with tenant_id, composite
-- foreign keys between shop tables. Amounts are bigint.
create table delivery_zone (
  id              bigint generated always as identity primary key,
  tenant_id       text not null references tenant(id),
  name            text not null,
  postal_codes    text[] not null default '{}',
  municipalities  text[] not null default '{}',
  fee_cents       bigint not null,
  min_order_cents bigint not null default 0,
  active          boolean not null default true,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         bigint not null default 0,
  constraint delivery_zone_tenant_id_id_key unique (tenant_id, id),
  constraint delivery_zone_tenant_id_name_key unique (tenant_id, name),
  constraint delivery_zone_amounts_not_negative check (fee_cents >= 0 and min_order_cents >= 0)
);

create index delivery_zone_tenant_id_active_idx on delivery_zone (tenant_id, active);

-- Where a delivery order goes: all nullable, empty for a pickup.
alter table customer_order
  add column recipient_name  text,
  add column recipient_phone text,
  add column address_line    text,
  add column postal_code     text,
  add column city            text,
  add column delivery_notes  text,
  add column zone_id         bigint,
  add constraint customer_order_zone_fkey
    foreign key (tenant_id, zone_id) references delivery_zone (tenant_id, id);
