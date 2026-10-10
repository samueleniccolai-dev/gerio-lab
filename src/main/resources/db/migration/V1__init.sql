-- Tenants (shops) and the domains that resolve to them.
-- PROGETTO.md §6. Global tables: no tenant_id column, not filtered by Hibernate's
-- discriminator multi-tenancy.
--
-- Check constraints are named so the application can map a violation to a clear
-- message, and a later migration can drop or replace one by name.
--
-- Every mutable table carries created_at, updated_at and version (ADR-0003):
-- the application sets the timestamps from the injected Clock, the defaults are
-- only a fallback for hand-written SQL; version backs JPA optimistic locking.

create table tenant (
  -- UUID as text (§5.1: TenantResolver returns a String). Not a full UUID format
  -- check: the one value that actually matters is the reserved platform sentinel
  -- (§5.2 point 5) -- if a tenant ever got that id, its rows would appear wherever
  -- the platform context reads global tables.
  id                     text primary key
                           constraint tenant_id_not_reserved
                           check (id <> '' and id <> '__platform__'),
  -- Becomes a subdomain (§5.4): one DNS label, lowercase.
  slug                   text not null unique
                           constraint tenant_slug_dns_label
                           check (slug ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
  -- A new shop starts as DRAFT: not public until the platform admin activates it
  -- (legal data complete, Stripe connected). Same idea as Shopify's private mode.
  status                 text not null default 'DRAFT'
                           constraint tenant_status_known
                           check (status in ('DRAFT', 'ACTIVE', 'SUSPENDED')),
  display_name           text not null,
  legal_name             text not null,
  vat_number             text,                           -- Italian VAT number (partita IVA)
  tax_code               text,                           -- Italian tax code (codice fiscale)
  rea                    text,
  pec                    text,                           -- certified email (posta elettronica certificata)
  email                  text not null,
  phone                  text,
  address_line           text,
  postal_code            text,
  city                   text,
  province               text,
  timezone               text not null default 'Europe/Rome',
  stripe_account_id      text unique,
  stripe_charges_enabled boolean not null default false,
  theme                  jsonb not null default '{}',
  settings               jsonb not null default '{}',    -- order cutoff, texts, social links...
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),
  version                bigint not null default 0
);

create table tenant_domain (
  id          bigint generated always as identity primary key,
  tenant_id   text not null references tenant(id),
  -- Stored exactly in the form the §5.2 filter looks up: lowercase, no port, no
  -- trailing dot, no leading "www." (the filter strips it). A row in any other
  -- form would be unreachable, or could collide with another tenant's hostname
  -- without "unique" noticing. Internationalized names go in punycode (xn--...).
  -- The last label (the TLD) must start with a letter: this rules out IP addresses,
  -- which must never be attached to a shop nor get a certificate via on-demand TLS
  -- (§11.4).
  hostname    text not null unique
                constraint tenant_domain_hostname_normalized
                check (
                  length(hostname) <= 253
                  and hostname ~ '^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$'
                  and hostname !~ '^www\.'
                ),
  is_primary  boolean not null default false,
  verified_at timestamptz,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  version     bigint not null default 0
);

-- Postgres does not index foreign key columns automatically; every lookup and
-- delete in tenant.id needs this to avoid a sequential scan on tenant_domain.
create index tenant_domain_tenant_id_idx on tenant_domain (tenant_id);

-- At most one primary domain per tenant (partial unique index: only rows where
-- is_primary is true compete for uniqueness).
create unique index tenant_domain_one_primary_idx on tenant_domain (tenant_id) where is_primary;
