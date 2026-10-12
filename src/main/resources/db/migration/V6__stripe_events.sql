-- Stripe webhook (giro 011, goal T5). Additions only.
-- stripe_event is a global table (SPEC 5.1, 6): no tenant_id, it is written before the shop of the
-- event is known. Its id is the Stripe event id, so a repeated event hits the primary key.
create table stripe_event (
  id           text primary key,
  type         text not null,
  account      text,
  received_at  timestamptz not null default now(),
  processed_at timestamptz
);

-- Names of SPEC section 6: the payment that paid the order and when.
alter table customer_order add column stripe_payment_intent_id text;
alter table customer_order add column paid_at timestamptz;
