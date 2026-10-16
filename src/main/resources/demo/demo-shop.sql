-- Demo shop "Fioreria Il Glicine": invented data only (docs/lab/giri/005-piano.md).
-- Not a Flyway migration: DemoSeed runs it at startup when app.demo.seed=true, after
-- the migrations. Idempotent: every insert upserts on the table's natural key, so
-- running it again changes nothing.

insert into tenant (id, slug, status, display_name, legal_name, vat_number, email, phone,
                    address_line, postal_code, city, province)
values ('00000000-0000-0000-0000-0000000de000', 'demo', 'ACTIVE', 'Fioreria Il Glicine',
        'Il Glicine di Lucia Ferri', 'IT00000000000', 'ciao@glicine.example', '+39 0583 000000',
        'Via dei Fossi 12', '55100', 'Lucca', 'LU')
on conflict (id) do update set
  slug = excluded.slug, status = excluded.status, display_name = excluded.display_name,
  legal_name = excluded.legal_name, vat_number = excluded.vat_number, email = excluded.email, phone = excluded.phone,
  address_line = excluded.address_line, postal_code = excluded.postal_code,
  city = excluded.city, province = excluded.province, updated_at = now();

insert into category (tenant_id, slug, name, position, visible)
values ('00000000-0000-0000-0000-0000000de000', 'bouquet', 'Bouquet', 1, true),
       ('00000000-0000-0000-0000-0000000de000', 'piante', 'Piante', 2, true),
       ('00000000-0000-0000-0000-0000000de000', 'composizioni', 'Composizioni', 3, true),
       ('00000000-0000-0000-0000-0000000de000', 'natale', 'Natale', 4, false)
on conflict (tenant_id, slug) do update set
  name = excluded.name, position = excluded.position, visible = excluded.visible,
  updated_at = now();

insert into product (tenant_id, category_id, slug, name, description, vat_rate_bp,
                     lead_time_days, active, position)
select '00000000-0000-0000-0000-0000000de000', c.id, p.slug, p.name, p.description, 1000,
       p.lead_time_days, p.active, p.position
from (values
  ('bouquet', 'bouquet-di-stagione', 'Bouquet di stagione',
   'Fiori freschi scelti ogni mattina al mercato, legati a mano.', 0, true, 1),
  ('bouquet', 'rose-rosse', 'Mazzo di rose rosse',
   'Rose rosse a stelo lungo, con verde decorativo.', 0, true, 2),
  ('bouquet', 'bouquet-sposa', 'Bouquet da sposa',
   'Su appuntamento, da concordare in negozio.', 0, true, 3),
  ('piante', 'orchidea-bianca', 'Orchidea Phalaenopsis bianca',
   'Pianta in vaso, fiorisce per settimane con poche cure.', 0, true, 1),
  ('composizioni', 'composizione-in-cesto', 'Composizione in cesto',
   'Fiori e verde in un cesto di vimini, pronta da regalare.', 1, true, 1),
  ('composizioni', 'corona-avvento', 'Corona dell''Avvento',
   'Corona di abete con quattro candele.', 0, false, 2),
  ('natale', 'stella-di-natale', 'Stella di Natale',
   'Pianta rossa della tradizione natalizia.', 0, true, 1)
) as p (category_slug, slug, name, description, lead_time_days, active, position)
join category c
  on c.tenant_id = '00000000-0000-0000-0000-0000000de000' and c.slug = p.category_slug
on conflict (tenant_id, slug) do update set
  category_id = excluded.category_id, name = excluded.name,
  description = excluded.description, vat_rate_bp = excluded.vat_rate_bp,
  lead_time_days = excluded.lead_time_days, active = excluded.active,
  position = excluded.position, updated_at = now();

insert into product_variant (tenant_id, product_id, name, price_cents, active, position)
select '00000000-0000-0000-0000-0000000de000', p.id, v.name, v.price_cents, v.active, v.position
from (values
  ('bouquet-di-stagione', 'Piccolo', 2500, true, 1),
  ('bouquet-di-stagione', 'Medio', 3500, true, 2),
  ('bouquet-di-stagione', 'Grande', 4900, true, 3),
  ('bouquet-di-stagione', 'Extra', 6900, false, 4),
  ('rose-rosse', '12 rose', 4500, true, 1),
  ('rose-rosse', '24 rose', 8500, true, 2),
  ('bouquet-sposa', 'Unico', 12000, false, 1),
  ('orchidea-bianca', 'Vaso 12 cm', 2990, true, 1),
  ('composizione-in-cesto', 'Media', 5500, true, 1),
  ('composizione-in-cesto', 'Grande', 7500, true, 2),
  ('corona-avvento', 'Unica', 3900, true, 1),
  ('stella-di-natale', 'Vaso 14 cm', 1800, true, 1)
) as v (product_slug, name, price_cents, active, position)
join product p
  on p.tenant_id = '00000000-0000-0000-0000-0000000de000' and p.slug = v.product_slug
on conflict (product_id, name) do update set
  price_cents = excluded.price_cents, active = excluded.active,
  position = excluded.position, updated_at = now();

-- Orders close at 12:00 shop time; the WhatsApp number is invented (giro 017).
update tenant
set settings = settings || '{"orderCutoff": "12:00", "whatsapp": "+393330000000"}'::jsonb
where id = '00000000-0000-0000-0000-0000000de000';

-- Delivery zones, inserted in this order (ids rise: the first zone wins on a shared postal code).
insert into delivery_zone (tenant_id, name, postal_codes, municipalities, fee_cents,
                           min_order_cents, active)
values ('00000000-0000-0000-0000-0000000de000', 'Lucca centro', array['55100'],
        array['Lucca'], 500, 2500, true),
       ('00000000-0000-0000-0000-0000000de000', 'Capannori e Porcari', array['55012', '55016'],
        array['Capannori', 'Porcari'], 900, 3500, true),
       ('00000000-0000-0000-0000-0000000de000', 'Versilia', array['55049'],
        array['Viareggio'], 1500, 5000, false)
on conflict (tenant_id, name) do update set
  postal_codes = excluded.postal_codes, municipalities = excluded.municipalities,
  fee_cents = excluded.fee_cents, min_order_cents = excluded.min_order_cents,
  active = excluded.active, updated_at = now();
