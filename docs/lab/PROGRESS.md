# PROGRESS — letto all'avvio di ogni giro, aggiornato alla fine (≤ 1 pagina)

**Obiettivo:** traguardi T1–T8 di `docs/lab/traguardi.json` — demo in produzione con ordine
end-to-end in Stripe test, lo stesso piano di Gerio al 4/10/2026.
**Giro corrente:** 008 · **Fase:** domanda · **PR aperta:** #11 · **Blocchi:** nessuno
**T0 del laboratorio:** 2026-10-11T09:43:32Z

## Prossimo

Dopo il merge di #10: giro su T4 (primo traguardo non passato).

## Ultimi giri

- 007 — T3 passato: prova a mano sul demo in produzione, solo documenti. PR #10.
- 006 — T3 in-corso: Caddy serve `demo.<dominio>` + smoke sul catalogo; 235 test. PR #9.
- 005 — T3 in-corso: catalogo, categorie, scheda prodotto, seme demo `@Startup`; 230 test. PR #8.

## Per l'umano

- **DOMANDA giro 008 (PR #11):** il carrello porta i primi form POST; serve `io.quarkus:quarkus-rest-csrf` (dipendenza nuova, SPEC §10.2)? A (raccomandata) sì · B no, Origin/SameSite fino a T5 · C token a mano (sconsigliata). Dettagli in `docs/lab/giri/008-piano.md`.

- Ratifiche da leggere: 503 dopo ogni deploy (n. 5); seme demo in prod (n. 8); testi scheda (n. 9).
- Avviso CI: `ubuntu-latest` passa a Ubuntu 26 dal 19/10 (`.github/` è protetto: decidi tu).
- Dubbio: FK di V3 senza `tenant_id`, da sistemare prima che il negozio modifichi il catalogo.

**Ultimo aggiornamento:** 2026-10-11 — giro 007.
