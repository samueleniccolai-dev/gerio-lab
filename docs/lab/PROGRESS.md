# PROGRESS — letto all'avvio di ogni giro, aggiornato alla fine (≤ 1 pagina)

**Obiettivo:** traguardi T1–T8 di `docs/lab/traguardi.json` — demo in produzione con ordine
end-to-end in Stripe test, lo stesso piano di Gerio al 4/10/2026.
**Giro corrente:** 011 · **Fase:** chiusura · **PR aperta:** #14 · **Blocchi:** nessuno
**T0 del laboratorio:** 2026-10-11T09:43:32Z

## Prossimo

Dopo il merge di #14: giro 012 su T5 (prova in produzione con Stripe test: servono le tue variabili), poi T6.

## Ultimi giri

- 011 — T5 in-corso: webhook Stripe firmato e idempotente, PAID/EXPIRED, pagina di esito, CSRF corretto; 312 test. PR #14.
- 010 — T5 in-corso: «Ordina e paga» crea ordine e sessione Stripe Checkout; stripe-java 34.0.0; 271 test. PR #13.
- 009 — T4 passato: prova del carrello sul demo in produzione, solo documenti. PR #12.

## Per l'umano

- PR #14: ratifiche n. 26 (CSRF) e n. 27 (firma sull'ora di sistema); migrazione V6.
- Ratifiche da leggere: carrello non svuotato, ordine nuovo se cambia (n. 22); checkout spento senza configurazione Stripe (n. 23); CSRF e webhook (n. 18).
- Per il giro 012: Stripe in modalità test, account collegato di prova per «Fioreria Il Glicine», endpoint webhook `/webhooks/stripe/connect`, variabili Stripe sul server.
- Avviso CI: `ubuntu-latest` passa a Ubuntu 26 dal 19/10 (`.github/` è protetto: decidi tu).
- Dubbio: FK di V3 senza `tenant_id`; FK variante di V4 senza `on delete`.

**Ultimo aggiornamento:** 2026-10-12 — giro 011.
