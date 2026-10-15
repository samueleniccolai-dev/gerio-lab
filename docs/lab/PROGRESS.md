# PROGRESS — letto all'avvio di ogni giro, aggiornato alla fine (≤ 1 pagina)

**Obiettivo:** traguardi T1–T8 di `docs/lab/traguardi.json` — demo in produzione con ordine
end-to-end in Stripe test, lo stesso piano di Gerio al 4/10/2026.
**Giro corrente:** 015 · **Fase:** domanda · **PR aperta:** #19 · **Blocchi:** nessuno
**T0 del laboratorio:** 2026-10-11T09:43:32Z

## Prossimo

Dopo il merge di #18: giro 015, email di conferma di T6. Porte a senso unico previste: `quarkus-mailer`, generatore PDF, provider SMTP UE (il giro si fermerà con una domanda).

## Ultimi giri

- 014 — T6 in-corso: pagina `/ordine/{token}` senza dati personali, link dall'esito; 361 test. PR #18.
- 013 — T5 passato: smoke webhook rigoroso, `prova-stripe.sh`, runbook §4; 355 test. PR #17 (revisione umana: infra/).
- 012 — T5 in-corso: variabili Stripe verso il server, chiavi live rifiutate, smoke del webhook; 329 test. PR #15.

## Per l'umano

- **DOMANDA giro 015 (PR #19):** posso aggiungere `io.quarkus:quarkus-mailer` (versione dal BOM)? Raccomandato A: solo mailer ora, provider SMTP e PDF dopo. Dettagli in `docs/lab/giri/015-piano.md`.

- Ratifica n. 32: la pagina di stato non mostra dati del cliente.
- Dopo il deploy: `curl -i https://demo.<LAB_BASE_DOMAIN>/ordine/<43 × A>` → 404.
- Caddy e app potrebbero inviare due `Referrer-Policy` (infra/, giro dedicato).
- Avviso CI: `ubuntu-latest` passa a Ubuntu 26 dal 19/10 (`.github/` è protetto: decidi tu).
- Dubbio: FK di V3 senza `tenant_id`; FK variante di V4 senza `on delete`.

**Ultimo aggiornamento:** 2026-10-15 — giro 014.
