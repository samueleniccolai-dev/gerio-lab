-- Catalog: categories, products and their variants (PROGETTO.md §6, first slice).
-- Additions only. Every table follows ADR-0003 point 8 (SchemaRulesTest): tenant_id
-- with a foreign key to tenant(id), unique (tenant_id, id), an index starting with
-- tenant_id. created_at, updated_at and version have the same defaults as V1.

create table category (
  id         bigint generated always as identity primary key,
  tenant_id  text not null references tenant(id),
  name       text not null,
  slug       text not null,
  position   int not null default 0,
  visible    boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version    bigint not null default 0,
  constraint category_tenant_id_id_key unique (tenant_id, id),
  constraint category_tenant_id_slug_key unique (tenant_id, slug)
);

create table product (
  id                  bigint generated always as identity primary key,
  tenant_id           text not null references tenant(id),
  category_id         bigint references category(id),
  name                text not null,
  slug                text not null,
  description         text,
  vat_rate_bp         int not null,                    -- 1000 = 10%
  lead_time_days      int not null default 0,          -- minimum days of preparation
  withdrawal_excluded boolean not null default true,   -- fresh flowers: no right of withdrawal
  active              boolean not null default true,
  position            int not null default 0,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  version             bigint not null default 0,
  constraint product_tenant_id_id_key unique (tenant_id, id),
  constraint product_tenant_id_slug_key unique (tenant_id, slug)
);

create index product_tenant_id_category_id_idx on product (tenant_id, category_id);

create table product_variant (
  id          bigint generated always as identity primary key,
  tenant_id   text not null references tenant(id),
  product_id  bigint not null references product(id),
  name        text not null,                           -- e.g. "Medio"
  price_cents bigint not null check (price_cents >= 0),   -- VAT included
  active      boolean not null default true,
  position    int not null default 0,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  version     bigint not null default 0,
  constraint product_variant_tenant_id_id_key unique (tenant_id, id),
  -- Natural key of a variant; the demo seed upserts on it.
  constraint product_variant_product_id_name_key unique (product_id, name)
);

create index product_variant_tenant_id_product_id_idx on product_variant (tenant_id, product_id);
