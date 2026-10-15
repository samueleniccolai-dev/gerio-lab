-- Outbox (giro 015, goal T6). Additions only. SPEC 6, 9: emails are queued in the transaction
-- that changes the order and sent later by a worker, with retries.
-- Global table (SPEC 5.1): tenant_id is a plain column, filled by hand.
create table outbox_message (
  id              bigint generated always as identity primary key,
  tenant_id       text references tenant(id),
  channel         text not null,                     -- EMAIL, TELEGRAM
  payload         jsonb not null,                    -- no personal data: ids only
  status          text not null default 'PENDING'
                    constraint outbox_message_status_known
                    check (status in ('PENDING', 'SENT', 'FAILED')),
  attempts        int not null default 0,
  next_attempt_at timestamptz not null default now(),
  last_error      text,
  created_at      timestamptz not null default now()
);

-- One confirmation per order, whatever the number of webhook events.
create unique index outbox_message_order_confirmation_uq
  on outbox_message ((payload->>'orderId'))
  where payload->>'kind' = 'ORDER_CONFIRMATION';

create index outbox_message_due_ix on outbox_message (status, next_attempt_at);
