-- Orders (giro 010, goal T5). Additions only. ADR-0003 point 8 (SchemaRulesTest): tenant_id with
-- a foreign key to tenant(id), unique (tenant_id, id), an index starting with tenant_id; foreign
-- keys between shop tables are composite with tenant_id. Amounts are bigint (DECISIONI n. 10).

create table customer_order (
  id                         bigint generated always as identity primary key,
  tenant_id                  text not null references tenant(id),
  -- Beyond SPEC section 6 (docs/scostamenti-4-10.md): the cart the order came from, and the
  -- state of the Stripe session attempts (idempotency keys "order-<id>-attempt-<n>").
  cart_id                    bigint,
  checkout_attempt           int not null default 0,
  checkout_expires_at        timestamptz,
  checkout_url               text,
  number                     text not null,                 -- 2026-0001, per shop and year
  public_token               text not null,                 -- secret link to the status page
  status                     text not null,
  fulfillment_type           text not null,
  customer_name              text not null,
  customer_email             text not null,
  customer_phone             text,
  delivery_date              date not null,
  subtotal_cents             bigint not null,
  delivery_fee_cents         bigint not null,
  total_cents                bigint not null,
  vat_breakdown              jsonb not null,                -- taxable amount and tax per rate
  terms_version              text not null,
  hold_expires_at            timestamptz,
  stripe_checkout_session_id text,
  created_at                 timestamptz not null default now(),
  updated_at                 timestamptz not null default now(),
  version                    bigint not null default 0,
  constraint customer_order_tenant_id_id_key unique (tenant_id, id),
  constraint customer_order_tenant_id_number_key unique (tenant_id, number),
  constraint customer_order_public_token_key unique (public_token),
  constraint customer_order_session_key unique (stripe_checkout_session_id),
  constraint customer_order_cart_fkey
    foreign key (tenant_id, cart_id) references cart (tenant_id, id) on delete set null (cart_id)
);

create index customer_order_tenant_id_day_idx on customer_order (tenant_id, delivery_date, status);
create index customer_order_tenant_id_cart_id_idx on customer_order (tenant_id, cart_id);

create table order_item (
  id               bigint generated always as identity primary key,
  tenant_id        text not null references tenant(id),
  order_id         bigint not null,
  variant_id       bigint,
  name_snapshot    text not null,                           -- product and variant at order time
  unit_price_cents bigint not null,
  quantity         int not null,
  vat_rate_bp      int not null,
  line_total_cents bigint not null,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  version          bigint not null default 0,
  constraint order_item_tenant_id_id_key unique (tenant_id, id),
  constraint order_item_order_fkey
    foreign key (tenant_id, order_id) references customer_order (tenant_id, id)
);

create index order_item_tenant_id_order_id_idx on order_item (tenant_id, order_id);

-- actor_user_id comes with the users (not yet).
create table order_event (
  id          bigint generated always as identity primary key,
  tenant_id   text not null references tenant(id),
  order_id    bigint not null,
  from_status text,
  to_status   text not null,
  note        text,
  at          timestamptz not null default now(),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  version     bigint not null default 0,
  constraint order_event_tenant_id_id_key unique (tenant_id, id),
  constraint order_event_order_fkey
    foreign key (tenant_id, order_id) references customer_order (tenant_id, id)
);

create index order_event_tenant_id_order_id_idx on order_event (tenant_id, order_id);

-- Order numbering (SPEC 7.2), written only with native SQL: insert ... on conflict ... returning.
create table tenant_counter (
  id         bigint generated always as identity primary key,
  tenant_id  text not null references tenant(id),
  name       text not null,
  year       int not null,
  value      int not null,
  constraint tenant_counter_tenant_id_id_key unique (tenant_id, id),
  constraint tenant_counter_tenant_id_name_year_key unique (tenant_id, name, year)
);
