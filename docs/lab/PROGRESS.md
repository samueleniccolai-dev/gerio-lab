# PROGRESS — letto all'avvio di ogni giro, aggiornato alla fine (≤ 1 pagina)

**Obiettivo:** traguardi T1–T8 di `docs/lab/traguardi.json` — demo in produzione con ordine
end-to-end in Stripe test, lo stesso piano di Gerio al 4/10/2026.
**Giro corrente:** 010 · **Fase:** chiusura · **PR aperta:** #13 · **Blocchi:** nessuno
**T0 del laboratorio:** 2026-10-11T09:43:32Z

## Prossimo

Dopo il merge di #13: giro 011 su T5 (webhook firmato e idempotente, `PAID`/`EXPIRED`, esclusione CSRF, pagina di esito; test del cambio prezzo tra tentativi).

## Ultimi giri

- 010 — T5 in-corso: «Ordina e paga» crea ordine e sessione Stripe Checkout; stripe-java 34.0.0; 271 test. PR #13.
- 009 — T4 passato: prova del carrello sul demo in produzione, solo documenti. PR #12.
- 008 — T4 in-corso: carrello con form classici e `quarkus-rest-csrf`; 251 test. PR #11.

## Per l'umano

- PR #13 in revisione umana (`pom.xml`, migrazione V5).
- Ratifiche da leggere: carrello non svuotato, ordine nuovo se cambia (n. 22); checkout spento senza configurazione Stripe (n. 23); CSRF e webhook (n. 18).
- Per il giro 012: Stripe in modalità test, account collegato di prova per «Fioreria Il Glicine», endpoint webhook `/webhooks/stripe/connect`, variabili Stripe sul server.
- Avviso CI: `ubuntu-latest` passa a Ubuntu 26 dal 19/10 (`.github/` è protetto: decidi tu).
- Dubbio: FK di V3 senza `tenant_id`; FK variante di V4 senza `on delete`.

**Ultimo aggiornamento:** 2026-10-12 — giro 010.
