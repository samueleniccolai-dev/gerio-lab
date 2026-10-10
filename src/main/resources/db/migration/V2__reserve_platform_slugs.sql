-- Slugs the platform keeps for itself (ADR-0004, PROGETTO.md §5.4).
--
-- A shop's slug becomes the subdomain slug.<base-domain>. A shop called "admin"
-- would own admin.<base-domain> for good, and the platform could never use it.
-- "www" is also unreachable as a shop: the tenant filter strips "www.", so
-- www.<base-domain> is always the platform. "mta-sts" is the subdomain email
-- security (MTA-STS) needs.
--
-- Additive and compatible with the previous code: no code creates tenants yet,
-- and no row exists in production. Adding a name later needs a new migration
-- that drops and recreates this constraint (after checking no shop uses it).

alter table tenant
  add constraint tenant_slug_not_reserved
  check (slug not in (
    'www', 'admin', 'api', 'app', 'internal', 'mail', 'static', 'status', 'img',
    'staging', 'dev', 'test', 'docs', 'help', 'blog', 'cdn', 'assets', 'mta-sts'
  ));

-- No "--" in positions 3-4 (RFC 5891): that form is reserved for encoded names
-- such as punycode "xn--...", which browsers display as Unicode. A slug like
-- "xn--fiorai-9ua" would show as a name that looks like another shop's
-- (phishing under the platform's own domain).
alter table tenant
  add constraint tenant_slug_not_encoded
  check (slug !~ '^..--');
