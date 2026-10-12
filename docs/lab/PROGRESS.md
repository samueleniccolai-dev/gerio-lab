# PROGRESS — letto all'avvio di ogni giro, aggiornato alla fine (≤ 1 pagina)

**Obiettivo:** traguardi T1–T8 di `docs/lab/traguardi.json` — demo in produzione con ordine
end-to-end in Stripe test, lo stesso piano di Gerio al 4/10/2026.
**Giro corrente:** 009 · **Fase:** chiusura · **PR aperta:** #12 · **Blocchi:** nessuno
**T0 del laboratorio:** 2026-10-11T09:43:32Z

## Prossimo

Dopo il merge di #12: giro su T5 (checkout Stripe in test, webhook idempotente; esclusione CSRF per il webhook).

## Ultimi giri

- 009 — T4 passato: prova del carrello sul demo in produzione, solo documenti. PR #12.
- 008 — T4 in-corso: carrello con form classici e `quarkus-rest-csrf`; 251 test. PR #11.
- 007 — T3 passato: prova a mano sul demo in produzione, solo documenti. PR #10.

## Per l'umano

- Ratifiche da leggere: token carrello solo server (n. 17); CSRF e webhook Stripe in T5 (n. 18).
- Avviso CI: `ubuntu-latest` passa a Ubuntu 26 dal 19/10 (`.github/` è protetto: decidi tu).
- Dubbio: FK di V3 senza `tenant_id`; FK variante di V4 senza `on delete`.

**Ultimo aggiornamento:** 2026-10-12 — giro 009.
