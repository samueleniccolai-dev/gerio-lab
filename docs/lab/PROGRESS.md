# PROGRESS — letto all'avvio di ogni giro, aggiornato alla fine (≤ 1 pagina)

**Obiettivo:** traguardi T1–T8 di `docs/lab/traguardi.json` — demo in produzione con ordine
end-to-end in Stripe test, lo stesso piano di Gerio al 4/10/2026.
**Giro corrente:** 013 · **Fase:** domanda · **PR aperta:** #17 · **Blocchi:** nessuno
**T0 del laboratorio:** 2026-10-11T09:43:32Z

## Prossimo

Dopo il merge di #15 e i tuoi passi (runbook §4): giro 013, prova in produzione con Stripe test (T5), poi T6.

## Ultimi giri

- 012 — T5 in-corso: variabili Stripe verso il server, account demo, chiavi live rifiutate, smoke del webhook; 329 test. PR #15 (revisione umana: infra/ e Giro006).
- 011 — T5 in-corso: webhook Stripe firmato e idempotente, PAID/EXPIRED, pagina di esito, CSRF corretto; 312 test. PR #14.
- 010 — T5 in-corso: «Ordina e paga» crea ordine e sessione Stripe Checkout; stripe-java 34.0.0; 271 test. PR #13.

## Per l'umano

- **DOMANDA giro 013 (PR #17):** in produzione il webhook Stripe risponde 503: mancano segreti/variabile Stripe test (runbook §4, passi 1–3). Opzioni: A (raccomandata) fai i passi e rilancia il deploy, poi paghi tu con la 4242; B solo strumenti, prova al giro 014; C pagamento automatico (sconsigliata). Dettagli in `docs/lab/giri/013-piano.md`.

- PR #15: revisione umana (infra/, curl finto di Giro006). Per il giro 013: account/chiavi Stripe test, webhook su dominio piattaforma, segreti in `produzione`, 3 variabili in `consegna.yml`.
- Ratifiche da leggere: carrello non svuotato, ordine nuovo se cambia (n. 22); checkout spento senza configurazione Stripe (n. 23); CSRF e webhook (n. 18).
- Avviso CI: `ubuntu-latest` passa a Ubuntu 26 dal 19/10 (`.github/` è protetto: decidi tu).
- Dubbio: FK di V3 senza `tenant_id`; FK variante di V4 senza `on delete`.

**Ultimo aggiornamento:** 2026-10-12 — giro 012.
